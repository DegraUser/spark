/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import scala.collection.mutable

import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.catalog.{InMemoryCatalog, SessionCatalog}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical.{RepartitionOperation, _}
import org.apache.spark.sql.catalyst.rules._
import org.apache.spark.sql.catalyst.trees.AlwaysProcess
import org.apache.spark.sql.catalyst.trees.TreePattern._
import org.apache.spark.sql.connector.catalog.CatalogManager
import org.apache.spark.sql.errors.QueryCompilationErrors
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.sql.util.SchemaUtils._
import org.apache.spark.util.Utils

/**
 * Abstract class all optimizers should inherit of, contains the standard batches (extending
 * Optimizers can override this.
 */
abstract class Optimizer(catalogManager: CatalogManager)
  extends RuleExecutor[LogicalPlan] {

  // Check for structural integrity of the plan in test mode.
  // Currently we check after the execution of each rule if a plan:
  // - is still resolved
  // - only host special expressions in supported operators
  // - has globally-unique attribute IDs
  // - optimized plan have same schema with previous plan.
  override protected def isPlanIntegral(
      previousPlan: LogicalPlan,
      currentPlan: LogicalPlan): Boolean = {
    !Utils.isTesting || (currentPlan.resolved &&
      !currentPlan.exists(PlanHelper.specialExpressionsInUnsupportedOperator(_).nonEmpty) &&
      LogicalPlanIntegrity.checkIfExprIdsAreGloballyUnique(currentPlan) &&
      DataType.equalsIgnoreNullability(previousPlan.schema, currentPlan.schema))
  }

  override protected val excludedOnceBatches: Set[String] =
    Set(
      "PartitionPruning",
      "RewriteSubquery",
      "Extract Python UDFs")

  protected def fixedPoint =
    FixedPoint(
      SQLConf.get.optimizerMaxIterations,
      maxIterationsSetting = SQLConf.OPTIMIZER_MAX_ITERATIONS.key)

  /**
   * Defines the default rule batches in the Optimizer.
   *
   * Implementations of this class should override this method, and [[nonExcludableRules]] if
   * necessary, instead of [[batches]]. The rule batches that eventually run in the Optimizer,
   * i.e., returned by [[batches]], will be (defaultBatches - (excludedRules - nonExcludableRules)).
   */
  def defaultBatches: Seq[Batch] = {
    val operatorOptimizationRuleSet =
      Seq(
// Operator push down
        // 将select的project列下推到relation上,
        // ds2.union(ds3).union(ds2).select("name").explain(true)
        // project下推，将name下推到3个relation上
        // union distinct 不能这样优化
        // https://blog.csdn.net/zg_hover/article/details/118633266
        // https://juejin.cn/post/7099061906211602446
        // select U.c1, U.c1 from (select 1 as c1, 2 as c2, 3 as c3 from t1 union all
        // select 1 as c1, 2 as c2, 3 as c3 from t2) U;
        // equals 将子查询union中的c3清理掉
        // select U.c1, U.c1 from (select 1 as c1, 2 as c2 from t1 union all
        // select 1 as c1, 2 as c2from t2) U;
        PushProjectionThroughUnion,
        // 注释中提到，将调整joinorder使得最开始执行的join至少有一个condition
        // https://issues.apache.org/jira/browse/SPARK-12032
        // where条件可以推到join里面，将outer join转换为inner join
        // 并且where条件顺序会影响join的顺序。
        // https://issues.apache.org/jira/browse/SPARK-17791
        // 星形join优化，将dim表提前到fact表做join优化
        // 将带有条件的join，优先推到下层
        // select * from it inner join t2 inner join t3 where t1.id = t3.id;
        // equals select *from it inner join t3 inner join t2 where t1.id=t3.id;
        ReorderJoin,
        // 将outer join转换为innerjoin如果有相关条件的话
        // 如果可以推导出join条件列非空，则可以将outerjoin 调整未inner join
        EliminateOuterJoin,
        // 谓词下推支持join、project、filter、aggregate
        // https://juejin.cn/post/7099061906211602446
        // 此处包含join下推和非join下推
        // join下推https://hover.blog.csdn.net/article/details/118884263
        // 参考这个博客，
        //  operator      preserved row table      null supplying table
        //  join predicate        no                    yes
        //  where predicate       yes                   no
        // 非join下推，参考https://zhuanlan.zhihu.com/p/432047724
        // 只含有filter的project，可以将非nondeterminated的filter下推到project下
        // agg下推，如果父查询condition是子查询agg条件的子集，则可以将condition下推
        // window下推，如果condition是window partition条件的子集，则condition可以下推
        // union下推，可以将非determinted的列下推
        PushDownPredicates,
        // left semi join可以将outer join转换为inner join
        // Project、Window、Union、Aggregate、PushPredicateThroughNonJoin 场景下，
        // 下推 LeftSemi/LeftAnti
        PushDownLeftSemiAntiJoin,
        // https://issues.apache.org/jira/browse/SPARK-19712
        // inner join和 semi join的顺序问题
        PushLeftSemiLeftAntiThroughJoin,
        // limit 下推
        // https://blog.csdn.net/zg_hover/article/details/119341554
        // 1. inion 可以将limit下给两个project
        // 2. out join下推到具体的孩子，如left outer join下推到左孩子
        // 3. full join不能优化
        // local limit指的是某一个查询的limit， global limit只得是最终的limit
        // 如果是agg only limit 1，则可以将limit 1下推
        LimitPushDown,
        // https://www.waitingforcode.com/apache-spark-sql/what-new-apache-spark-3.2.0-performance-optimizations/read
        // windows函数的partition如果为空则可以下推limit和order by 条件
        LimitPushDownThroughWindow,
        // project 下推,列下推
        ColumnPruning,
        // https://issues.apache.org/jira/browse/SPARK-37450
        GenerateOptimization,
// Operator combine
        // https://juejin.cn/post/7099062368981745695
        // https://www.jianshu.com/p/efa0ace796c0 coalesce和repartion的区别
        // repartition是shuffle partition个数，coalesce是不shuffle尽量生成个数
        CollapseRepartition,
        // https://issues.apache.org/jira/browse/SPARK-27123
        // 类似子查询扁平化，将子查询与主查询的project合并
        // https://juejin.cn/post/7099062368981745695
        CollapseProject,
        // 关于NTH_VALUE窗口函数的优化
        // https://issues.apache.org/jira/browse/SPARK-32934
        OptimizeWindowFunctions,
        // 如果多个窗口函数的partition条件并且order by条件一致，并且output
        // 类型一致，则可以合并两个函数一起算
        CollapseWindow,
        // https://juejin.cn/post/7099062368981745695
        // 可以将filter通过and连接，构成平级关系而不是上下级关系
        CombineFilters,
        // https://issues.apache.org/jira/browse/SPARK-33442
        // 消除limit，如果max_row < limit
        EliminateLimits,
        // https://issues.apache.org/jira/browse/SPARK-28330
        // offset类似limit可以指定从offset开始返回行
        RewriteOffsets,
        // 合并多个union为一个union，最后做一次aggregation
        CombineUnions,
// Constant folding and strength reduction
        // https://issues.apache.org/jira/browse/SPARK-33806
        // https://issues.apache.org/jira/browse/SPARK-34030
        // 将常量partition合并未1个
        OptimizeRepartition,
        // https://issues.apache.org/jira/browse/SPARK-20636
        // 如果两个相邻的window函数的partition 列是包含关系，
        // 则window的shuffle动作可以合并为1个
        TransposeWindow,
        // null值水平传递，可以传递到scan或者agg中
        NullPropagation,
        // https://issues.apache.org/jira/browse/SPARK-36665
        // BooleanSimplification should be able to do more simplifications
        // for Not operators applying following rules
        // 1. Not(null) == null
        // e.g. IsNull(Not(...)) can be IsNull(...)
        // 2. (Not(a) = b) == (a = Not(b))
        // e.g. Not(...) = true can be (...) = false
        // 3.(a != b) == (a = Not(b))
        // e.g. (...) != true can be (...) = false
        NullDownPropagation,
        // 常量传递，如果是等值条件并且是and连接，则可以将常量
        // 传递到其它的表达式上
        ConstantPropagation,
        // 常量折叠，将常量表达式合并
        // /**
        // * Replace attributes with aliases of the original foldable expressions if possible.
        // * Other optimizations will take advantage of the propagated foldable expressions. For example,
        // * this rule can optimize
        // * {{{
        // *   SELECT 1.0 x, 'abc' y, Now() z ORDER BY x, y, 3
        // * }}}
        // * to
        // * {{{
        // *   SELECT 1.0 x, 'abc' y, Now() z ORDER BY 1.0, 'abc', Now()
        // * }}}
        // * and other rules can further optimize it and remove the ORDER BY operator.
        // */
        // 常量表达式的别名，可以替换为常量表达式，供其它算子优化
        FoldablePropagation,
        // https://juejin.cn/post/7099062368981745695
        // in(list)中有重复的常量，可以合并。
        OptimizeIn,
        // 提前计算可以合并的常量优化
        ConstantFolding,
        // aggregate中filter的消除
        EliminateAggregateFilter,
        // https://juejin.cn/post/7099062368981745695
        // select 1 + (id + 2) + 3 from t;
        // 可以优化为select id + 6 from t;
        ReorderAssociativeOperator,
        // https://juejin.cn/post/7099062368981745695
        // select * from t where id like '%abc'
        // can optimizer
        // select * from t where id endwith('abc')
        // e.g like 'abc%' equal startwith('abc')
        LikeSimplification,
        // https://juejin.cn/post/7099062368981745695
        // 如果谓词包含多个bool表达式，如果一个表达式能够反映出计算结果，就不用结算其它的表达式了
        // select * from t where(id > 10 and a < 100) or (id > 10);
        // equal select * from t where id > 10 and id is not null;
        BooleanSimplification,
        // https://juejin.cn/post/7099062368981745695
        // 如果常量谓词能够在优化阶段算出，则可以直接返回值
        // select if(2>1, 'a', 'b');
        // equal select a as if(2>1, 'a', 'b');
        SimplifyConditionals,
        PushFoldableIntoBranches,
        RemoveDispensableExpressions,
        // https://juejin.cn/post/7099062368981745695
        SimplifyBinaryComparison,
        // https://juejin.cn/post/7099062368981745695
        // filter null can be replace with false
        ReplaceNullWithFalseInPredicate,
        // https://juejin.cn/post/7099062368981745695

        SimplifyConditionalsInPredicate,
        // https://juejin.cn/post/7099062368981745695
        // select * from t where 1>0;
        // equal select * from t;
        PruneFilters,
        // https://juejin.cn/post/7099062368981745695/
        // select cast('abc' as string) as c1;
        // 'abc'和string类型相同，可以相处cast
        SimplifyCasts,
        // // https://juejin.cn/post/7099062368981745695
        // 大小写转换替代，如果内层大小写转换被外层替换，则可以消除内部重写
        // select upper(lower('abcDEF'))
        // equal select upper('abcDEF')
        // equal select 'ABCDEF' as upper(lower('abcDEF'))
        SimplifyCaseConversionExpressions,
        // https://juejin.cn/post/7099062368981745695
        // 相关子查询转join, 如果是标量子查询必须转换为single row join
        // select a from ()
        RewriteCorrelatedScalarSubquery,
        // lateralSubquery转join
        RewriteLateralSubquery,
        // https://juejin.cn/post/7099062368981745695
        // 消除无用的序列化和反序列化
        // spark.range(10).map(_*1).map(_+2)
        // .map(_*1)序列化不需要可以删除，range(10).map(_+2)就不需要序列化了
        EliminateSerialization,
        // https://juejin.cn/post/7099062368981745695
        // 别名删除
        // select id as id from t; equal select id from t;
        RemoveRedundantAliases,
        // https://www.waitingforcode.com/apache-spark-sql/what-new-apache-spark-3.2.0-performance-optimizations/read
        // select number from (select num, count(*) from t group by number) group by num;
        // equal select num from (select num, count(*) from t group by number);
        RemoveRedundantAggregates,
        // https://issues.apache.org/jira/browse/SPARK-24994
        // select * from t where a = 100; a smallint 100 int
        // we push 100 to parquet
        UnwrapCastInBinaryComparison,
        // 空操作符可以删除
        RemoveNoopOperators,
        OptimizeUpdateFields,
        // https://juejin.cn/post/7099062368981745695
        // select array('a', 'b', 'c')[0] as c1;
        // equal select a as c1; e.g
        SimplifyExtractValueOps,
        // 可以优化schema只解码project中的列
        OptimizeCsvJsonExprs,
        // concat 函数级联合并
        // select concat("abc", concat("def","ghi") as c1;
        // equal s select concat("abc", "defghi") as c1;
        CombineConcats,
        // https://github.com/apache/spark/pull/34929#discussion_r779653096
        PushdownPredicatesAndPruneColumnsForCTEDef) ++
        extendedOperatorOptimizationRules

    val operatorOptimizationBatch: Seq[Batch] = {
      Batch("Operator Optimization before Inferring Filters", fixedPoint,
        operatorOptimizationRuleSet: _*) ::
      Batch("Infer Filters", Once,
      // https://issues.apache.org/jira/browse/SPARK-33544
        InferFiltersFromGenerate,
        InferFiltersFromConstraints) ::
      Batch("Operator Optimization after Inferring Filters", fixedPoint,
        operatorOptimizationRuleSet: _*) ::
      // Set strategy to Once to avoid pushing filter every time because we do not change the
      // join condition.
      Batch("Push extra predicate through join", fixedPoint,
        // 将join条件下推到孩子节点
        PushExtraPredicateThroughJoin,
        PushDownPredicates) :: Nil
    }

    // https://juejin.cn/post/7099060822856433695
    // this batch reference the url
    val batches = (
      // for cte https://issues.apache.org/jira/browse/SPARK-37670
    Batch("Finish Analysis", Once, FinishAnalysis) ::
    //////////////////////////////////////////////////////////////////////////////////////////
    // Optimizer rules start here
    //////////////////////////////////////////////////////////////////////////////////////////
    // select max(distinct a) from t;
      // equals select max(a) from t; 消除distinct
      Batch("Eliminate Distinct", Once, EliminateDistinct) ::
    // - Do the first call of CombineUnions before starting the major Optimizer rules,
    //   since it can reduce the number of iteration and the other rules could add/move
    //   extra operators between two adjacent Union operators.
    // - Call CombineUnions again in Batch("Operator Optimizations"),
    //   since the other rules might make two separate Unions operators adjacent.
    Batch("Inline CTE", Once,
      InlineCTE()) ::
    Batch("Union", Once,
      RemoveNoopOperators,
      CombineUnions,
      RemoveNoopUnion) ::
    Batch("OptimizeLimitZero", Once,
      OptimizeLimitZero) ::
    // Run this once earlier. This might simplify the plan and reduce cost of optimizer.
    // For example, a query such as Filter(LocalRelation) would go through all the heavy
    // optimizer rules that are triggered when there is a filter
    // (e.g. InferFiltersFromConstraints). If we run this batch earlier, the query becomes just
    // LocalRelation and does not trigger many rules.
    Batch("LocalRelation early", fixedPoint,
      // 将LocalRelation上的本地操作，转换为一个localRelation
      // https://www.waitingforcode.com/apache-spark-sql/writing-custom-optimization-apache-spark-sql-union-rewriter-mvp-version/read#custom_node
      ConvertToLocalRelation,
      // https://jaceklaskowski.gitbooks.io/mastering-spark-sql/content/spark-sql-Optimizer-PropagateEmptyRelation.html
      // 空表的一个优化
      PropagateEmptyRelation,
      // PropagateEmptyRelation can change the nullability of an attribute from nullable to
      // non-nullable when an empty relation child of a Union is removed
      UpdateAttributeNullability) ::
    Batch("Pullup Correlated Expressions", Once,
      // https://issues.apache.org/jira/browse/SPARK-36063
      // one row 优化为子查询消除
      OptimizeOneRowRelationSubquery,
      // https://docs.google.com/document/d/1QDZ8JwU63RwGFS6KVF54Rjj9ZJyK33d49ZWbjFBaIgU/edit#
      // 设计文档,有些条件是不能上提的，可能会影响结果正确性
      // select * from t where exists(select 1 from t1 where t.b = t1.b limit 1);
      // 此sql是 符合条件之后limit 1， 如果对子查询先进行limit 1，再进行等值判断，则会出错
      PullupCorrelatedPredicates) ::
    // Subquery batch applies the optimizer rules recursively. Therefore, it makes no sense
    // to enforce idempotence on it and we change this batch from Once to FixedPoint(1).
    Batch("Subquery", FixedPoint(1),
      // https://juejin.cn/post/7099060822856433695
      // 消除子查询的order by条件
      // select id, (select max(a) from t1 where t.id = t1.id order by max(a) ) from t;
      // 标量子查询只会返回一条结果，所以order by无用，可以省略掉
      OptimizeSubqueries) ::
      // https://juejin.cn/post/7099061906211602446
      // 重写可参考
    Batch("Replace Operators", fixedPoint,
      // https://juejin.cn/post/7099061906211602446
      // 使用union all、agg等将except all重写
      RewriteExceptAll,
      // https://juejin.cn/post/7099061906211602446
      // 使用union all、agg等将intersect all语句
      RewriteIntersectAll,
      // https://juejin.cn/post/7099061906211602446
      // 使用semijoin重写intersect语句
      ReplaceIntersectWithSemiJoin,
      // except重写
      ReplaceExceptWithFilter,
      ReplaceExceptWithAntiJoin,
      ReplaceDistinctWithAggregate,
      ReplaceDeduplicateWithAggregate) ::
    Batch("Aggregate", fixedPoint,
      // select b from t group by b, "a", "b";
      // equals select b from t group by b; can remove agg exp "a","b"
      RemoveLiteralFromGroupExpressions,
      // select b from t group by b, b;
      // equals select b from t group by b;
      RemoveRepetitionFromGroupExpressions) :: Nil ++
    operatorOptimizationBatch) :+
    Batch("Clean Up Temporary CTE Info", Once, CleanUpTempCTEInfo) :+
    // This batch rewrites plans after the operator optimization and
    // before any batches that depend on stats.
    Batch("Pre CBO Rules", Once, preCBORules: _*) :+
    // This batch pushes filters and projections into scan nodes. Before this batch, the logical
    // plan may contain nodes that do not report stats. Anything that uses stats must run after
    // this batch.
    Batch("Early Filter and Projection Push-Down", Once, earlyScanPushDownRules: _*) :+
    Batch("Update CTE Relation Stats", Once, UpdateCTERelationStats) :+
    // Since join costs in AQP can change between multiple runs, there is no reason that we have an
    // idempotence enforcement on this batch. We thus make it FixedPoint(1) instead of Once.
    Batch("Join Reorder", FixedPoint(1),
      // join reorder
      CostBasedJoinReorder) :+
    Batch("Eliminate Sorts", Once,
      // select * from (select * from t order by a) t order by a;
      // equals select * from (select * from t order by a) t;
      EliminateSorts) :+
    Batch("Decimal Optimizations", fixedPoint,
      DecimalAggregates) :+
    // This batch must run after "Decimal Optimizations", as that one may change the
    // aggregate distinct column
    Batch("Distinct Aggregate Rewrite", Once,
      // 重写聚合函数种带有distinct的语句
      // select count(distinct a) from t group by b; =>
      // select count(a) from (select a, b from t group by b, a) group by b;
      RewriteDistinctAggregates) :+
    Batch("Object Expressions Optimization", fixedPoint,
      EliminateMapObjects,
      CombineTypedFilters,
      ObjectSerializerPruning,
      ReassignLambdaVariableID) :+
    Batch("LocalRelation", fixedPoint,
      ConvertToLocalRelation,
      // https://jaceklaskowski.gitbooks.io/mastering-spark-sql/content/spark-sql-Optimizer-PropagateEmptyRelation.html
      PropagateEmptyRelation,
      // PropagateEmptyRelation can change the nullability of an attribute from nullable to
      // non-nullable when an empty relation child of a Union is removed
      UpdateAttributeNullability) :+
    // one row or less table can runing sort、agg、distinct、e.g
    Batch("Optimize One Row Plan", fixedPoint, OptimizeOneRowPlan) :+
    // The following batch should be executed after batch "Join Reorder" and "LocalRelation".
    Batch("Check Cartesian Products", Once,
      CheckCartesianProducts) :+
    Batch("RewriteSubquery", Once,
      // not exists in/not in to semi/anti join
      RewritePredicateSubquery,
      ColumnPruning,
      // prjoect -> project   project
      CollapseProject,
      RemoveRedundantAliases,
      RemoveNoopOperators) :+
    // This batch must be executed after the `RewriteSubquery` batch, which creates joins.
    Batch("NormalizeFloatingNumbers", Once, NormalizeFloatingNumbers) :+
    Batch("ReplaceUpdateFieldsExpression", Once, ReplaceUpdateFieldsExpression)

    // remove any batches with no rules. this may happen when subclasses do not add optional rules.
    batches.filter(_.rules.nonEmpty)
  }

  /**
   * Defines rules that cannot be excluded from the Optimizer even if they are specified in
   * SQL config "excludedRules".
   *
   * Implementations of this class can override this method if necessary. The rule batches
   * that eventually run in the Optimizer, i.e., returned by [[batches]], will be
   * (defaultBatches - (excludedRules - nonExcludableRules)).
   */
  def nonExcludableRules: Seq[String] =
    FinishAnalysis.ruleName ::
      RewriteDistinctAggregates.ruleName ::
      ReplaceDeduplicateWithAggregate.ruleName ::
      ReplaceIntersectWithSemiJoin.ruleName ::
      ReplaceExceptWithFilter.ruleName ::
      ReplaceExceptWithAntiJoin.ruleName ::
      RewriteExceptAll.ruleName ::
      RewriteIntersectAll.ruleName ::
      ReplaceDistinctWithAggregate.ruleName ::
      PullupCorrelatedPredicates.ruleName ::
      RewriteCorrelatedScalarSubquery.ruleName ::
      RewritePredicateSubquery.ruleName ::
      NormalizeFloatingNumbers.ruleName ::
      ReplaceUpdateFieldsExpression.ruleName ::
      RewriteLateralSubquery.ruleName :: Nil

  /**
   * Apply finish-analysis rules for the entire plan including all subqueries.
   */
  object FinishAnalysis extends Rule[LogicalPlan] {
    // Technically some of the rules in Finish Analysis are not optimizer rules and belong more
    // in the analyzer, because they are needed for correctness (e.g. ComputeCurrentTime).
    // However, because we also use the analyzer to canonicalized queries (for view definition),
    // we do not eliminate subqueries or compute current time in the analyzer.
    private val rules = Seq(
      // 消除hint，将hint下推到具体的算子上
      // https://juejin.cn/post/7099060822856433695
      EliminateResolvedHint,
      // 消除子查询
      // Subquery可以直接对接project或者LogicalRDD(ScanXO)
      EliminateSubqueryAliases,
      // 消除view
      // 再逻辑计划中，subquery孩子为view，view的孩子为一个query，
      // 可以合并subquery、view、及project
      EliminateView,
      // 表达式替换，将其它数据库的表达式替换为等价的spark表达式
      ReplaceExpressions,
      // https://juejin.cn/post/7099060822856433695
      // 非相关子查询重写
      // select * from t where exists(select b from t1 where a = 1);
      // equals select * from t where (select 1 from (select * from t1 where t1.a = 10) limit 1) is not null
      RewriteNonCorrelatedExists,
      // https://github.com/apache/spark/pull/32396
      // 在agg下方挂在project算子，将复杂的运算下推到project算子，以保证agg算子的分组函数没有复杂的函数
      PullOutGroupingExpressions,
      // 如果一个语句中包含多个计算时间的表达式，则同时计算时间，以保证sql时间的一致性
      ComputeCurrentTime,
      // replace database and catalog name
      ReplaceCurrentLike(catalogManager),
      // 静态时间戳计算
      SpecialDatetimeValues,
      // as of jon spark semantic
      RewriteAsOfJoin)

    override def apply(plan: LogicalPlan): LogicalPlan = {
      rules.foldLeft(plan) { case (sp, rule) => rule.apply(sp) }
        .transformAllExpressionsWithPruning(_.containsPattern(PLAN_EXPRESSION)) {
          case s: SubqueryExpression =>
            val Subquery(newPlan, _) = apply(Subquery.fromExpression(s))
            s.withNewPlan(newPlan)
        }
    }
  }

  /**
   * Optimize all the subqueries inside expression.
   */
  object OptimizeSubqueries extends Rule[LogicalPlan] {
    private def removeTopLevelSort(plan: LogicalPlan): LogicalPlan = {
      if (!plan.containsPattern(SORT)) {
        return plan
      }
      plan match {
        case Sort(_, _, child) => child
        case Project(fields, child) => Project(fields, removeTopLevelSort(child))
        case other => other
      }
    }
    def apply(plan: LogicalPlan): LogicalPlan = plan.transformAllExpressionsWithPruning(
      _.containsPattern(PLAN_EXPRESSION), ruleId) {
      case s: SubqueryExpression =>
        val Subquery(newPlan, _) = Optimizer.this.execute(Subquery.fromExpression(s))
        // At this point we have an optimized subquery plan that we are going to attach
        // to this subquery expression. Here we can safely remove any top level sort
        // in the plan as tuples produced by a subquery are un-ordered.
        s.withNewPlan(removeTopLevelSort(newPlan))
    }
  }

  /**
   * Update CTE reference stats.
   */
  object UpdateCTERelationStats extends Rule[LogicalPlan] {
    override def apply(plan: LogicalPlan): LogicalPlan = {
      if (!plan.isInstanceOf[Subquery] && plan.containsPattern(CTE)) {
        val statsMap = mutable.HashMap.empty[Long, Statistics]
        updateCTEStats(plan, statsMap)
      } else {
        plan
      }
    }

    private def updateCTEStats(
        plan: LogicalPlan,
        statsMap: mutable.HashMap[Long, Statistics]): LogicalPlan = plan match {
      case WithCTE(child, cteDefs) =>
        val newDefs = cteDefs.map { cteDef =>
          val newDef = updateCTEStats(cteDef, statsMap)
          statsMap.put(cteDef.id, newDef.stats)
          newDef.asInstanceOf[CTERelationDef]
        }
        WithCTE(updateCTEStats(child, statsMap), newDefs)
      case c: CTERelationRef =>
        statsMap.get(c.cteId).map(s => c.withNewStats(Some(s))).getOrElse(c)
      case _ if plan.containsPattern(CTE) =>
        plan
          .withNewChildren(plan.children.map(child => updateCTEStats(child, statsMap)))
          .transformExpressionsWithPruning(_.containsAllPatterns(PLAN_EXPRESSION, CTE)) {
            case e: SubqueryExpression =>
              e.withNewPlan(updateCTEStats(e.plan, statsMap))
          }
      case _ => plan
    }
  }

  /**
   * Override to provide additional rules for the operator optimization batch.
   */
  def extendedOperatorOptimizationRules: Seq[Rule[LogicalPlan]] = Nil

  /**
   * Override to provide additional rules for early projection and filter pushdown to scans.
   */
  def earlyScanPushDownRules: Seq[Rule[LogicalPlan]] = Nil

  /**
   * Override to provide additional rules for rewriting plans after operator optimization rules and
   * before any cost-based optimization rules that depend on stats.
   */
  def preCBORules: Seq[Rule[LogicalPlan]] = Nil

  /**
   * Returns (defaultBatches - (excludedRules - nonExcludableRules)), the rule batches that
   * eventually run in the Optimizer.
   *
   * Implementations of this class should override [[defaultBatches]], and [[nonExcludableRules]]
   * if necessary, instead of this method.
   */
  final override def batches: Seq[Batch] = {
    val excludedRulesConf =
      SQLConf.get.optimizerExcludedRules.toSeq.flatMap(Utils.stringToSeq)
    val excludedRules = excludedRulesConf.filter { ruleName =>
      val nonExcludable = nonExcludableRules.contains(ruleName)
      if (nonExcludable) {
        logWarning(s"Optimization rule '${ruleName}' was not excluded from the optimizer " +
          s"because this rule is a non-excludable rule.")
      }
      !nonExcludable
    }
    if (excludedRules.isEmpty) {
      defaultBatches
    } else {
      defaultBatches.flatMap { batch =>
        val filteredRules = batch.rules.filter { rule =>
          val exclude = excludedRules.contains(rule.ruleName)
          if (exclude) {
            logInfo(s"Optimization rule '${rule.ruleName}' is excluded from the optimizer.")
          }
          !exclude
        }
        if (batch.rules == filteredRules) {
          Some(batch)
        } else if (filteredRules.nonEmpty) {
          Some(Batch(batch.name, batch.strategy, filteredRules: _*))
        } else {
          logInfo(s"Optimization batch '${batch.name}' is excluded from the optimizer " +
            s"as all enclosed rules have been excluded.")
          None
        }
      }
    }
  }
}

/**
 * Remove useless DISTINCT:
 *   1. For some aggregate expression, e.g.: MAX and MIN.
 *   2. If the distinct semantics is guaranteed by child.
 *
 * This rule should be applied before RewriteDistinctAggregates.
 */
object EliminateDistinct extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(AGGREGATE)) {
    case agg: Aggregate =>
      agg.transformExpressionsWithPruning(_.containsPattern(AGGREGATE_EXPRESSION)) {
        case ae: AggregateExpression if ae.isDistinct &&
          isDuplicateAgnostic(ae.aggregateFunction) =>
          ae.copy(isDistinct = false)

        case ae: AggregateExpression if ae.isDistinct &&
          agg.child.distinctKeys.exists(
            _.subsetOf(ExpressionSet(ae.aggregateFunction.children.filterNot(_.foldable)))) =>
          ae.copy(isDistinct = false)
      }
  }

  def isDuplicateAgnostic(af: AggregateFunction): Boolean = af match {
    case _: Max => true
    case _: Min => true
    case _: BitAndAgg => true
    case _: BitOrAgg => true
    case _: CollectSet => true
    case _: First => true
    case _: Last => true
    case _ => false
  }
}

/**
 * Remove useless FILTER clause for aggregate expressions.
 * This rule should be applied before RewriteDistinctAggregates.
 */
object EliminateAggregateFilter extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan.transformAllExpressionsWithPruning(
    _.containsAllPatterns(AGGREGATE_EXPRESSION, TRUE_OR_FALSE_LITERAL), ruleId)  {
    case ae @ AggregateExpression(_, _, _, Some(Literal.TrueLiteral), _) =>
      ae.copy(filter = None)
    case AggregateExpression(af: DeclarativeAggregate, _, _, Some(Literal.FalseLiteral), _) =>
      val initialProject = SafeProjection.create(af.initialValues)
      val evalProject = SafeProjection.create(af.evaluateExpression :: Nil, af.aggBufferAttributes)
      val initialBuffer = initialProject(EmptyRow)
      val internalRow = evalProject(initialBuffer)
      Literal.create(internalRow.get(0, af.dataType), af.dataType)
    case AggregateExpression(af: ImperativeAggregate, _, _, Some(Literal.FalseLiteral), _) =>
      val buffer = new SpecificInternalRow(af.aggBufferAttributes.map(_.dataType))
      af.initialize(buffer)
      Literal.create(af.eval(buffer), af.dataType)
  }
}

/**
 * An optimizer used in test code.
 *
 * To ensure extendability, we leave the standard rules in the abstract optimizer rules, while
 * specific rules go to the subclasses
 */
object SimpleTestOptimizer extends SimpleTestOptimizer

class SimpleTestOptimizer extends Optimizer(
  new CatalogManager(
    FakeV2SessionCatalog,
    new SessionCatalog(new InMemoryCatalog, EmptyFunctionRegistry, EmptyTableFunctionRegistry)))

/**
 * Remove redundant aliases from a query plan. A redundant alias is an alias that does not change
 * the name or metadata of a column, and does not deduplicate it.
 */
object RemoveRedundantAliases extends Rule[LogicalPlan] {

  /**
   * Create an attribute mapping from the old to the new attributes. This function will only
   * return the attribute pairs that have changed.
   */
  private def createAttributeMapping(current: LogicalPlan, next: LogicalPlan)
      : Seq[(Attribute, Attribute)] = {
    current.output.zip(next.output).filterNot {
      case (a1, a2) => a1.semanticEquals(a2)
    }
  }

  /**
   * Remove the top-level alias from an expression when it is redundant.
   */
  private def removeRedundantAlias(e: Expression, excludeList: AttributeSet): Expression = e match {
    // Alias with metadata can not be stripped, or the metadata will be lost.
    // If the alias name is different from attribute name, we can't strip it either, or we
    // may accidentally change the output schema name of the root plan.
    case a @ Alias(attr: Attribute, name)
      if (a.metadata == Metadata.empty || a.metadata == attr.metadata) &&
        name == attr.name &&
        !excludeList.contains(attr) &&
        !excludeList.contains(a) =>
      attr
    case a => a
  }

  /**
   * Remove redundant alias expression from a LogicalPlan and its subtree. A set of excludes is used
   * to prevent the removal of seemingly redundant aliases used to deduplicate the input for a
   * (self) join or to prevent the removal of top-level subquery attributes.
   */
  private def removeRedundantAliases(plan: LogicalPlan, excluded: AttributeSet): LogicalPlan = {
    if (!plan.containsPattern(ALIAS)) {
      return plan
    }
    plan match {
      // We want to keep the same output attributes for subqueries. This means we cannot remove
      // the aliases that produce these attributes
      case Subquery(child, correlated) =>
        Subquery(removeRedundantAliases(child, excluded ++ child.outputSet), correlated)

      // A join has to be treated differently, because the left and the right side of the join are
      // not allowed to use the same attributes. We use an exclude list to prevent us from creating
      // a situation in which this happens; the rule will only remove an alias if its child
      // attribute is not on the black list.
      case Join(left, right, joinType, condition, hint) =>
        val newLeft = removeRedundantAliases(left, excluded ++ right.outputSet)
        val newRight = removeRedundantAliases(right, excluded ++ newLeft.outputSet)
        val mapping = AttributeMap(
          createAttributeMapping(left, newLeft) ++
          createAttributeMapping(right, newRight))
        val newCondition = condition.map(_.transform {
          case a: Attribute => mapping.getOrElse(a, a)
        })
        Join(newLeft, newRight, joinType, newCondition, hint)

      case _ =>
        // Remove redundant aliases in the subtree(s).
        val currentNextAttrPairs = mutable.Buffer.empty[(Attribute, Attribute)]
        val newNode = plan.mapChildren { child =>
          val newChild = removeRedundantAliases(child, excluded)
          currentNextAttrPairs ++= createAttributeMapping(child, newChild)
          newChild
        }

        // Create the attribute mapping. Note that the currentNextAttrPairs can contain duplicate
        // keys in case of Union (this is caused by the PushProjectionThroughUnion rule); in this
        // case we use the first mapping (which should be provided by the first child).
        val mapping = AttributeMap(currentNextAttrPairs.toSeq)

        // Create a an expression cleaning function for nodes that can actually produce redundant
        // aliases, use identity otherwise.
        val clean: Expression => Expression = plan match {
          case _: Project => removeRedundantAlias(_, excluded)
          case _: Aggregate => removeRedundantAlias(_, excluded)
          case _: Window => removeRedundantAlias(_, excluded)
          case _ => identity[Expression]
        }

        // Transform the expressions.
        newNode.mapExpressions { expr =>
          clean(expr.transform {
            case a: Attribute => mapping.get(a).map(_.withName(a.name)).getOrElse(a)
          })
        }
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = removeRedundantAliases(plan, AttributeSet.empty)
}

/**
 * Remove no-op operators from the query plan that do not make any modifications.
 */
object RemoveNoopOperators extends Rule[LogicalPlan] {

  def apply(plan: LogicalPlan): LogicalPlan = plan.transformUpWithPruning(
    _.containsAnyPattern(PROJECT, WINDOW), ruleId) {
    // Eliminate no-op Projects
    case p @ Project(projectList, child) if child.sameOutput(p) =>
      val newChild = child match {
        case p: Project =>
          p.copy(projectList = restoreOriginalOutputNames(p.projectList, projectList.map(_.name)))
        case agg: Aggregate =>
          agg.copy(aggregateExpressions =
            restoreOriginalOutputNames(agg.aggregateExpressions, projectList.map(_.name)))
        case _ =>
          child
      }
      if (newChild.output.zip(projectList).forall { case (a1, a2) => a1.name == a2.name }) {
        newChild
      } else {
        p
      }

    // Eliminate no-op Window
    case w: Window if w.windowExpressions.isEmpty => w.child
  }
}

/**
 * Smplify the children of `Union` or remove no-op `Union` from the query plan that
 * do not make any modifications to the query.
 */
object RemoveNoopUnion extends Rule[LogicalPlan] {
  /**
   * This only removes the `Project` that has only attributes or aliased attributes
   * from its child.
   */
  private def removeAliasOnlyProject(plan: LogicalPlan): LogicalPlan = plan match {
    case p @ Project(projectList, child) =>
      val aliasOnly = projectList.length == child.output.length &&
        projectList.zip(child.output).forall {
          case (Alias(left: Attribute, _), right) => left.semanticEquals(right)
          case (left: Attribute, right) => left.semanticEquals(right)
          case _ => false
        }
      if (aliasOnly) {
        child
      } else {
        p
      }
    case _ => plan
  }

  private def simplifyUnion(u: Union): LogicalPlan = {
    val uniqueChildren = mutable.ArrayBuffer.empty[LogicalPlan]
    val uniqueChildrenKey = mutable.HashSet.empty[LogicalPlan]

    u.children.foreach { c =>
      val key = removeAliasOnlyProject(c).canonicalized
      if (!uniqueChildrenKey.contains(key)) {
        uniqueChildren += c
        uniqueChildrenKey += key
      }
    }
    if (uniqueChildren.size == 1) {
      u.children.head
    } else {
      u.copy(children = uniqueChildren.toSeq)
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan.transformUpWithPruning(
    _.containsAllPatterns(DISTINCT_LIKE, UNION)) {
    case d @ Distinct(u: Union) =>
      d.withNewChildren(Seq(simplifyUnion(u)))
    case d @ Deduplicate(_, u: Union) =>
      d.withNewChildren(Seq(simplifyUnion(u)))
  }
}

/**
 * Pushes down [[LocalLimit]] beneath UNION ALL and joins.
 */
object LimitPushDown extends Rule[LogicalPlan] {

  private def stripGlobalLimitIfPresent(plan: LogicalPlan): LogicalPlan = {
    plan match {
      case GlobalLimit(_, child) => child
      case _ => plan
    }
  }

  private def maybePushLocalLimit(limitExp: Expression, plan: LogicalPlan): LogicalPlan = {
    (limitExp, plan.maxRowsPerPartition) match {
      case (IntegerLiteral(newLimit), Some(childMaxRows)) if newLimit < childMaxRows =>
        // If the child has a cap on max rows per partition and the cap is larger than
        // the new limit, put a new LocalLimit there.
        LocalLimit(limitExp, stripGlobalLimitIfPresent(plan))

      case (_, None) =>
        // If the child has no cap, put the new LocalLimit.
        LocalLimit(limitExp, stripGlobalLimitIfPresent(plan))

      case _ =>
        // Otherwise, don't put a new LocalLimit.
        plan
    }
  }

  private def pushLocalLimitThroughJoin(limitExpr: Expression, join: Join): Join = {
    join.joinType match {
      case RightOuter => join.copy(right = maybePushLocalLimit(limitExpr, join.right))
      case LeftOuter => join.copy(left = maybePushLocalLimit(limitExpr, join.left))
      case _: InnerLike if join.condition.isEmpty =>
        join.copy(
          left = maybePushLocalLimit(limitExpr, join.left),
          right = maybePushLocalLimit(limitExpr, join.right))
      case LeftSemi | LeftAnti if join.condition.isEmpty =>
        join.copy(
          left = maybePushLocalLimit(limitExpr, join.left),
          right = maybePushLocalLimit(Literal(1, IntegerType), join.right))
      case _ => join
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(LIMIT), ruleId) {
    // Adding extra Limits below UNION ALL for children which are not Limit or do not have Limit
    // descendants whose maxRow is larger. This heuristic is valid assuming there does not exist any
    // Limit push-down rule that is unable to infer the value of maxRows.
    // Note: right now Union means UNION ALL, which does not de-duplicate rows, so it is safe to
    // pushdown Limit through it. Once we add UNION DISTINCT, however, we will not be able to
    // pushdown Limit.
    case LocalLimit(exp, u: Union) =>
      LocalLimit(exp, u.copy(children = u.children.map(maybePushLocalLimit(exp, _))))

    // Add extra limits below JOIN:
    // 1. For LEFT OUTER and RIGHT OUTER JOIN, we push limits to the left and right sides,
    //    respectively.
    // 2. For INNER and CROSS JOIN, we push limits to both the left and right sides if join
    //    condition is empty.
    // 3. For LEFT SEMI and LEFT ANTI JOIN, we push limits to the left side if join condition
    //    is empty.
    // It's not safe to push limits below FULL OUTER JOIN in the general case without a more
    // invasive rewrite. We also need to ensure that this limit pushdown rule will not eventually
    // introduce limits on both sides if it is applied multiple times. Therefore:
    //   - If one side is already limited, stack another limit on top if the new limit is smaller.
    //     The redundant limit will be collapsed by the CombineLimits rule.
    case LocalLimit(exp, join: Join) =>
      LocalLimit(exp, pushLocalLimitThroughJoin(exp, join))
    // There is a Project between LocalLimit and Join if they do not have the same output.
    case LocalLimit(exp, project @ Project(_, join: Join)) =>
      LocalLimit(exp, project.copy(child = pushLocalLimitThroughJoin(exp, join)))
    // Push down limit 1 through Aggregate and turn Aggregate into Project if it is group only.
    case Limit(le @ IntegerLiteral(1), a: Aggregate) if a.groupOnly =>
      Limit(le, Project(a.aggregateExpressions, LocalLimit(le, a.child)))
    case Limit(le @ IntegerLiteral(1), p @ Project(_, a: Aggregate)) if a.groupOnly =>
      Limit(le, p.copy(child = Project(a.aggregateExpressions, LocalLimit(le, a.child))))
  }
}

/**
 * Pushes Project operator to both sides of a Union operator.
 * Operations that are safe to pushdown are listed as follows.
 * Union:
 * Right now, Union means UNION ALL, which does not de-duplicate rows. So, it is
 * safe to pushdown Filters and Projections through it. Filter pushdown is handled by another
 * rule PushDownPredicates. Once we add UNION DISTINCT, we will not be able to pushdown Projections.
 */
object PushProjectionThroughUnion extends Rule[LogicalPlan] with PredicateHelper {

  /**
   * Maps Attributes from the left side to the corresponding Attribute on the right side.
   */
  private def buildRewrites(left: LogicalPlan, right: LogicalPlan): AttributeMap[Attribute] = {
    assert(left.output.size == right.output.size)
    AttributeMap(left.output.zip(right.output))
  }

  /**
   * Rewrites an expression so that it can be pushed to the right side of a
   * Union or Except operator. This method relies on the fact that the output attributes
   * of a union/intersect/except are always equal to the left child's output.
   */
  private def pushToRight[A <: Expression](e: A, rewrites: AttributeMap[Attribute]) = {
    val result = e transform {
      case a: Attribute => rewrites(a)
    } match {
      // Make sure exprId is unique in each child of Union.
      case Alias(child, alias) => Alias(child, alias)()
      case other => other
    }

    // We must promise the compiler that we did not discard the names in the case of project
    // expressions.  This is safe since the only transformation is from Attribute => Attribute.
    result.asInstanceOf[A]
  }

  def pushProjectionThroughUnion(projectList: Seq[NamedExpression], u: Union): Seq[LogicalPlan] = {
    val newFirstChild = Project(projectList, u.children.head)
    val newOtherChildren = u.children.tail.map { child =>
      val rewrites = buildRewrites(u.children.head, child)
      Project(projectList.map(pushToRight(_, rewrites)), child)
    }
    newFirstChild +: newOtherChildren
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsAllPatterns(UNION, PROJECT)) {

    // Push down deterministic projection through UNION ALL
    case Project(projectList, u: Union)
        if projectList.forall(_.deterministic) && u.children.nonEmpty =>
      u.copy(children = pushProjectionThroughUnion(projectList, u))
  }
}

/**
 * Attempts to eliminate the reading of unneeded columns from the query plan.
 *
 * Since adding Project before Filter conflicts with PushPredicatesThroughProject, this rule will
 * remove the Project p2 in the following pattern:
 *
 *   p1 @ Project(_, Filter(_, p2 @ Project(_, child))) if p2.outputSet.subsetOf(p2.inputSet)
 *
 * p2 is usually inserted by this rule and useless, p1 could prune the columns anyway.
 */
object ColumnPruning extends Rule[LogicalPlan] {

  def apply(plan: LogicalPlan): LogicalPlan = removeProjectBeforeFilter(
    plan.transformWithPruning(AlwaysProcess.fn, ruleId) {
    // Prunes the unused columns from project list of Project/Aggregate/Expand
    case p @ Project(_, p2: Project) if !p2.outputSet.subsetOf(p.references) =>
      p.copy(child = p2.copy(projectList = p2.projectList.filter(p.references.contains)))
    case p @ Project(_, a: Aggregate) if !a.outputSet.subsetOf(p.references) =>
      p.copy(
        child = a.copy(aggregateExpressions = a.aggregateExpressions.filter(p.references.contains)))
    case a @ Project(_, e @ Expand(_, _, grandChild)) if !e.outputSet.subsetOf(a.references) =>
      val newOutput = e.output.filter(a.references.contains(_))
      val newProjects = e.projections.map { proj =>
        proj.zip(e.output).filter { case (_, a) =>
          newOutput.contains(a)
        }.unzip._1
      }
      a.copy(child = Expand(newProjects, newOutput, grandChild))

    // Prune and drop AttachDistributedSequence if the produced attribute is not referred.
    case p @ Project(_, a @ AttachDistributedSequence(_, grandChild))
        if !p.references.contains(a.sequenceAttr) =>
      p.copy(child = prunedChild(grandChild, p.references))

    // Prunes the unused columns from child of `DeserializeToObject`
    case d @ DeserializeToObject(_, _, child) if !child.outputSet.subsetOf(d.references) =>
      d.copy(child = prunedChild(child, d.references))

    // Prunes the unused columns from child of Aggregate/Expand/Generate/ScriptTransformation
    case a @ Aggregate(_, _, child) if !child.outputSet.subsetOf(a.references) =>
      a.copy(child = prunedChild(child, a.references))
    case f @ FlatMapGroupsInPandas(_, _, _, child) if !child.outputSet.subsetOf(f.references) =>
      f.copy(child = prunedChild(child, f.references))
    case e @ Expand(_, _, child) if !child.outputSet.subsetOf(e.references) =>
      e.copy(child = prunedChild(child, e.references))

    // prune unrequired references
    // There are 2 types of pruning here:
    // 1. For attributes in g.child.outputSet that is not used by the generator nor the project,
    //    we directly remove it from the output list of g.child.
    // 2. For attributes that is not used by the project but it is used by the generator, we put
    //    it in g.unrequiredChildIndex to save memory usage.
    case GeneratorUnrequiredChildrenPruning(rewrittenPlan) => rewrittenPlan

    // prune unrequired nested fields from `Generate`.
    case GeneratorNestedColumnAliasing(rewrittenPlan) => rewrittenPlan

    // Eliminate unneeded attributes from right side of a Left Existence Join.
    case j @ Join(_, right, LeftExistence(_), _, _) =>
      j.copy(right = prunedChild(right, j.references))

    // all the columns will be used to compare, so we can't prune them
    case p @ Project(_, _: SetOperation) => p
    case p @ Project(_, _: Distinct) => p
    // Eliminate unneeded attributes from children of Union.
    case p @ Project(_, u: Union) =>
      if (!u.outputSet.subsetOf(p.references)) {
        val firstChild = u.children.head
        val newOutput = prunedChild(firstChild, p.references).output
        // pruning the columns of all children based on the pruned first child.
        val newChildren = u.children.map { p =>
          val selected = p.output.zipWithIndex.filter { case (a, i) =>
            newOutput.contains(firstChild.output(i))
          }.map(_._1)
          Project(selected, p)
        }
        p.copy(child = u.withNewChildren(newChildren))
      } else {
        p
      }

    // Prune unnecessary window expressions
    case p @ Project(_, w: Window) if !w.windowOutputSet.subsetOf(p.references) =>
      p.copy(child = w.copy(
        windowExpressions = w.windowExpressions.filter(p.references.contains)))

    // Prune WithCTE
    case p @ Project(_, w: WithCTE) =>
      if (!w.outputSet.subsetOf(p.references)) {
        p.copy(child = w.withNewPlan(prunedChild(w.plan, p.references)))
      } else {
        p
      }

    // Can't prune the columns on LeafNode
    case p @ Project(_, _: LeafNode) => p

    case NestedColumnAliasing(rewrittenPlan) => rewrittenPlan

    // for all other logical plans that inherits the output from it's children
    // Project over project is handled by the first case, skip it here.
    case p @ Project(_, child) if !child.isInstanceOf[Project] =>
      val required = child.references ++ p.references
      if (!child.inputSet.subsetOf(required)) {
        val newChildren = child.children.map(c => prunedChild(c, required))
        p.copy(child = child.withNewChildren(newChildren))
      } else {
        p
      }
  })

  /** Applies a projection only when the child is producing unnecessary attributes */
  def prunedChild(c: LogicalPlan, allReferences: AttributeSet): LogicalPlan =
    if (!c.outputSet.subsetOf(allReferences)) {
      Project(c.output.filter(allReferences.contains), c)
    } else {
      c
    }

  /**
   * The Project before Filter is not necessary but conflict with PushPredicatesThroughProject,
   * so remove it. Since the Projects have been added top-down, we need to remove in bottom-up
   * order, otherwise lower Projects can be missed.
   */
  private def removeProjectBeforeFilter(plan: LogicalPlan): LogicalPlan = plan transformUp {
    case p1 @ Project(_, f @ Filter(_, p2 @ Project(_, child)))
      if p2.outputSet.subsetOf(child.outputSet) &&
        // We only remove attribute-only project.
        p2.projectList.forall(_.isInstanceOf[AttributeReference]) =>
      p1.copy(child = f.copy(child = child))
  }
}

/**
 * Combines two [[Project]] operators into one and perform alias substitution,
 * merging the expressions into one single expression for the following cases.
 * 1. When two [[Project]] operators are adjacent.
 * 2. When two [[Project]] operators have LocalLimit/Sample/Repartition operator between them
 *    and the upper project consists of the same number of columns which is equal or aliasing.
 *    `GlobalLimit(LocalLimit)` pattern is also considered.
 */
object CollapseProject extends Rule[LogicalPlan] with AliasHelper {

  def apply(plan: LogicalPlan): LogicalPlan = {
    val alwaysInline = conf.getConf(SQLConf.COLLAPSE_PROJECT_ALWAYS_INLINE)
    plan.transformUpWithPruning(_.containsPattern(PROJECT), ruleId) {
      case p1 @ Project(_, p2: Project)
          if canCollapseExpressions(p1.projectList, p2.projectList, alwaysInline) =>
        p2.copy(projectList = buildCleanedProjectList(p1.projectList, p2.projectList))
      case p @ Project(_, agg: Aggregate)
          if canCollapseExpressions(p.projectList, agg.aggregateExpressions, alwaysInline) &&
             canCollapseAggregate(p, agg) =>
        agg.copy(aggregateExpressions = buildCleanedProjectList(
          p.projectList, agg.aggregateExpressions))
      case Project(l1, g @ GlobalLimit(_, limit @ LocalLimit(_, p2 @ Project(l2, _))))
        if isRenaming(l1, l2) =>
        val newProjectList = buildCleanedProjectList(l1, l2)
        g.copy(child = limit.copy(child = p2.copy(projectList = newProjectList)))
      case Project(l1, limit @ LocalLimit(_, p2 @ Project(l2, _))) if isRenaming(l1, l2) =>
        val newProjectList = buildCleanedProjectList(l1, l2)
        limit.copy(child = p2.copy(projectList = newProjectList))
      case Project(l1, r @ Repartition(_, _, p @ Project(l2, _))) if isRenaming(l1, l2) =>
        r.copy(child = p.copy(projectList = buildCleanedProjectList(l1, p.projectList)))
      case Project(l1, s @ Sample(_, _, _, _, p2 @ Project(l2, _))) if isRenaming(l1, l2) =>
        s.copy(child = p2.copy(projectList = buildCleanedProjectList(l1, p2.projectList)))
    }
  }

  /**
   * Check if we can collapse expressions safely.
   */
  def canCollapseExpressions(
      consumers: Seq[Expression],
      producers: Seq[NamedExpression],
      alwaysInline: Boolean): Boolean = {
    canCollapseExpressions(consumers, getAliasMap(producers), alwaysInline)
  }

  /**
   * Check if we can collapse expressions safely.
   */
  def canCollapseExpressions(
      consumers: Seq[Expression],
      producerMap: Map[Attribute, Expression],
      alwaysInline: Boolean = false): Boolean = {
    // We can only collapse expressions if all input expressions meet the following criteria:
    // - The input is deterministic.
    // - The input is only consumed once OR the underlying input expression is cheap.
    consumers.flatMap(collectReferences)
      .groupBy(identity)
      .mapValues(_.size)
      .forall {
        case (reference, count) =>
          val producer = producerMap.getOrElse(reference, reference)
          producer.deterministic && (count == 1 || alwaysInline || {
            val relatedConsumers = consumers.filter(_.references.contains(reference))
            val extractOnly = relatedConsumers.forall(isExtractOnly(_, reference))
            shouldInline(producer, extractOnly)
          })
      }
  }

  @scala.annotation.tailrec
  private def isExtractOnly(expr: Expression, ref: Attribute): Boolean = expr match {
    case a: Alias => isExtractOnly(a.child, ref)
    case e: ExtractValue => isExtractOnly(e.children.head, ref)
    case a: Attribute => a.semanticEquals(ref)
    case _ => false
  }

  /**
   * A project cannot be collapsed with an aggregate when there are correlated scalar
   * subqueries in the project list, because currently we only allow correlated subqueries
   * in aggregate if they are also part of the grouping expressions. Otherwise the plan
   * after subquery rewrite will not be valid.
   */
  private def canCollapseAggregate(p: Project, a: Aggregate): Boolean = {
    p.projectList.forall(_.collect {
      case s: ScalarSubquery if s.outerAttrs.nonEmpty => s
    }.isEmpty)
  }

  def buildCleanedProjectList(
      upper: Seq[NamedExpression],
      lower: Seq[NamedExpression]): Seq[NamedExpression] = {
    val aliases = getAliasMap(lower)
    upper.map(replaceAliasButKeepName(_, aliases))
  }

  /**
   * Check if the given expression is cheap that we can inline it.
   */
  private def shouldInline(e: Expression, extractOnlyConsumer: Boolean): Boolean = e match {
    case _: Attribute | _: OuterReference => true
    case _ if e.foldable => true
    // PythonUDF is handled by the rule ExtractPythonUDFs
    case _: PythonUDF => true
    // Alias and ExtractValue are very cheap.
    case _: Alias | _: ExtractValue => e.children.forall(shouldInline(_, extractOnlyConsumer))
    // These collection create functions are not cheap, but we have optimizer rules that can
    // optimize them out if they are only consumed by ExtractValue, so we need to allow to inline
    // them to avoid perf regression. As an example:
    //   Project(s.a, s.b, Project(create_struct(a, b, c) as s, child))
    // We should collapse these two projects and eventually get Project(a, b, child)
    case _: CreateNamedStruct | _: CreateArray | _: CreateMap | _: UpdateFields =>
      extractOnlyConsumer
    case _ => false
  }

  /**
   * Return all the references of the given expression without deduplication, which is different
   * from `Expression.references`.
   */
  private def collectReferences(e: Expression): Seq[Attribute] = e.collect {
    case a: Attribute => a
  }

  private def isRenaming(list1: Seq[NamedExpression], list2: Seq[NamedExpression]): Boolean = {
    list1.length == list2.length && list1.zip(list2).forall {
      case (e1, e2) if e1.semanticEquals(e2) => true
      case (Alias(a: Attribute, _), b) if a.metadata == Metadata.empty && a.name == b.name => true
      case _ => false
    }
  }
}

/**
 * Combines adjacent [[RepartitionOperation]] and [[RebalancePartitions]] operators
 */
object CollapseRepartition extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformUpWithPruning(
    _.containsAnyPattern(REPARTITION_OPERATION, REBALANCE_PARTITIONS), ruleId) {
    // Case 1: When a Repartition has a child of Repartition or RepartitionByExpression,
    // 1) When the top node does not enable the shuffle (i.e., coalesce API), but the child
    //   enables the shuffle. Returns the child node if the last numPartitions is bigger;
    //   otherwise, keep unchanged.
    // 2) In the other cases, returns the top node with the child's child
    case r @ Repartition(_, _, child: RepartitionOperation) => (r.shuffle, child.shuffle) match {
      case (false, true) => if (r.numPartitions >= child.numPartitions) child else r
      case _ => r.copy(child = child.child)
    }
    // Case 2: When a RepartitionByExpression has a child of global Sort, Repartition or
    // RepartitionByExpression we can remove the child.
    case r @ RepartitionByExpression(_, child @ (Sort(_, true, _) | _: RepartitionOperation), _) =>
      r.withNewChildren(child.children)
    // Case 3: When a RebalancePartitions has a child of local or global Sort, Repartition or
    // RepartitionByExpression we can remove the child.
    case r @ RebalancePartitions(_, child @ (_: Sort | _: RepartitionOperation), _) =>
      r.withNewChildren(child.children)
    // Case 4: When a RebalancePartitions has a child of RebalancePartitions we can remove the
    // child.
    case r @ RebalancePartitions(_, child: RebalancePartitions, _) =>
      r.withNewChildren(child.children)
  }
}

/**
 * Replace RepartitionByExpression numPartitions to 1 if all partition expressions are foldable
 * and user not specify.
 */
object OptimizeRepartition extends Rule[LogicalPlan] {
  override def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(REPARTITION_OPERATION), ruleId) {
    case r @ RepartitionByExpression(partitionExpressions, _, numPartitions)
      if partitionExpressions.nonEmpty && partitionExpressions.forall(_.foldable) &&
        numPartitions.isEmpty =>
      r.copy(optNumPartitions = Some(1))
  }
}

/**
 * Replaces first(col) to nth_value(col, 1) for better performance.
 */
object OptimizeWindowFunctions extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.resolveExpressionsWithPruning(
    _.containsPattern(WINDOW_EXPRESSION), ruleId) {
    case we @ WindowExpression(AggregateExpression(first: First, _, _, _, _),
        WindowSpecDefinition(_, orderSpec, frameSpecification: SpecifiedWindowFrame))
        if orderSpec.nonEmpty && frameSpecification.frameType == RowFrame &&
          frameSpecification.lower == UnboundedPreceding &&
          (frameSpecification.upper == UnboundedFollowing ||
            frameSpecification.upper == CurrentRow) =>
      we.copy(windowFunction = NthValue(first.child, Literal(1), first.ignoreNulls))
  }
}

/**
 * Collapse Adjacent Window Expression.
 * - If the partition specs and order specs are the same and the window expression are
 *   independent and are of the same window function type, collapse into the parent.
 */
object CollapseWindow extends Rule[LogicalPlan] {
  private def windowsCompatible(w1: Window, w2: Window): Boolean = {
    w1.partitionSpec == w2.partitionSpec &&
      w1.orderSpec == w2.orderSpec &&
      w1.references.intersect(w2.windowOutputSet).isEmpty &&
      w1.windowExpressions.nonEmpty && w2.windowExpressions.nonEmpty &&
      // This assumes Window contains the same type of window expressions. This is ensured
      // by ExtractWindowFunctions.
      WindowFunctionType.functionType(w1.windowExpressions.head) ==
        WindowFunctionType.functionType(w2.windowExpressions.head)
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan.transformUpWithPruning(
    _.containsPattern(WINDOW), ruleId) {
    case w1 @ Window(we1, _, _, w2 @ Window(we2, _, _, grandChild))
        if windowsCompatible(w1, w2) =>
      w1.copy(windowExpressions = we2 ++ we1, child = grandChild)

    case w1 @ Window(we1, _, _, Project(pl, w2 @ Window(we2, _, _, grandChild)))
        if windowsCompatible(w1, w2) && w1.references.subsetOf(grandChild.outputSet) =>
      Project(
        pl ++ w1.windowOutputSet,
        w1.copy(windowExpressions = we2 ++ we1, child = grandChild))
  }
}

/**
 * Transpose Adjacent Window Expressions.
 * - If the partition spec of the parent Window expression is compatible with the partition spec
 *   of the child window expression, transpose them.
 */
object TransposeWindow extends Rule[LogicalPlan] {
  private def compatiblePartitions(ps1 : Seq[Expression], ps2: Seq[Expression]): Boolean = {
    ps1.length < ps2.length && ps2.take(ps1.length).permutations.exists(ps1.zip(_).forall {
      case (l, r) => l.semanticEquals(r)
    })
  }

  private def windowsCompatible(w1: Window, w2: Window): Boolean = {
    w1.references.intersect(w2.windowOutputSet).isEmpty &&
      w1.expressions.forall(_.deterministic) &&
      w2.expressions.forall(_.deterministic) &&
      compatiblePartitions(w1.partitionSpec, w2.partitionSpec)
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan.transformUpWithPruning(
    _.containsPattern(WINDOW), ruleId) {
    case w1 @ Window(_, _, _, w2 @ Window(_, _, _, grandChild))
      if windowsCompatible(w1, w2) =>
      Project(w1.output, w2.copy(child = w1.copy(child = grandChild)))

    case w1 @ Window(_, _, _, Project(pl, w2 @ Window(_, _, _, grandChild)))
      if windowsCompatible(w1, w2) && w1.references.subsetOf(grandChild.outputSet) =>
      Project(
        pl ++ w1.windowOutputSet,
        w2.copy(child = w1.copy(child = grandChild)))
  }
}

/**
 * Infers filters from [[Generate]], such that rows that would have been removed
 * by this [[Generate]] can be removed earlier - before joins and in data sources.
 */
object InferFiltersFromGenerate extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformUpWithPruning(
    _.containsPattern(GENERATE)) {
    case generate @ Generate(g, _, false, _, _, _) if canInferFilters(g) =>
      assert(g.children.length == 1)
      val input = g.children.head
      // Generating extra predicates here has overheads/risks:
      //   - We may evaluate expensive input expressions multiple times.
      //   - We may infer too many constraints later.
      //   - The input expression may fail to be evaluated under ANSI mode. If we reorder the
      //     predicates and evaluate the input expression first, we may fail the query unexpectedly.
      // To be safe, here we only generate extra predicates if the input is an attribute.
      // Note that, foldable input is also excluded here, to avoid constant filters like
      // 'size([1, 2, 3]) > 0'. These do not show up in child's constraints and then the
      // idempotence will break.
      if (input.isInstanceOf[Attribute]) {
        // Exclude child's constraints to guarantee idempotency
        val inferredFilters = ExpressionSet(
          Seq(GreaterThan(Size(input), Literal(0)), IsNotNull(input))
        ) -- generate.child.constraints

        if (inferredFilters.nonEmpty) {
          generate.copy(child = Filter(inferredFilters.reduce(And), generate.child))
        } else {
          generate
        }
      } else {
        generate
      }
  }

  private def canInferFilters(g: Generator): Boolean = g match {
    case _: ExplodeBase => true
    case _: Inline => true
    case _ => false
  }
}

/**
 * Generate a list of additional filters from an operator's existing constraint but remove those
 * that are either already part of the operator's condition or are part of the operator's child
 * constraints. These filters are currently inserted to the existing conditions in the Filter
 * operators and on either side of Join operators.
 *
 * Note: While this optimization is applicable to a lot of types of join, it primarily benefits
 * Inner and LeftSemi joins.
 */
object InferFiltersFromConstraints extends Rule[LogicalPlan]
  with PredicateHelper with ConstraintHelper {

  def apply(plan: LogicalPlan): LogicalPlan = {
    if (conf.constraintPropagationEnabled) {
      inferFilters(plan)
    } else {
      plan
    }
  }

  private def inferFilters(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsAnyPattern(FILTER, JOIN)) {
    case filter @ Filter(condition, child) =>
      val newFilters = filter.constraints --
        (child.constraints ++ splitConjunctivePredicates(condition))
      if (newFilters.nonEmpty) {
        Filter(And(newFilters.reduce(And), condition), child)
      } else {
        filter
      }

    case join @ Join(left, right, joinType, conditionOpt, _) =>
      joinType match {
        // For inner join, we can infer additional filters for both sides. LeftSemi is kind of an
        // inner join, it just drops the right side in the final output.
        case _: InnerLike | LeftSemi =>
          val allConstraints = getAllConstraints(left, right, conditionOpt)
          val newLeft = inferNewFilter(left, allConstraints)
          val newRight = inferNewFilter(right, allConstraints)
          join.copy(left = newLeft, right = newRight)

        // For right outer join, we can only infer additional filters for left side.
        case RightOuter =>
          val allConstraints = getAllConstraints(left, right, conditionOpt)
          val newLeft = inferNewFilter(left, allConstraints)
          join.copy(left = newLeft)

        // For left join, we can only infer additional filters for right side.
        case LeftOuter | LeftAnti =>
          val allConstraints = getAllConstraints(left, right, conditionOpt)
          val newRight = inferNewFilter(right, allConstraints)
          join.copy(right = newRight)

        case _ => join
      }
  }

  private def getAllConstraints(
      left: LogicalPlan,
      right: LogicalPlan,
      conditionOpt: Option[Expression]): ExpressionSet = {
    val baseConstraints = left.constraints.union(right.constraints)
      .union(ExpressionSet(conditionOpt.map(splitConjunctivePredicates).getOrElse(Nil)))
    baseConstraints.union(inferAdditionalConstraints(baseConstraints))
  }

  private def inferNewFilter(plan: LogicalPlan, constraints: ExpressionSet): LogicalPlan = {
    val newPredicates = constraints
      .union(constructIsNotNullConstraints(constraints, plan.output))
      .filter { c =>
        c.references.nonEmpty && c.references.subsetOf(plan.outputSet) && c.deterministic
      } -- plan.constraints
    if (newPredicates.isEmpty) {
      plan
    } else {
      Filter(newPredicates.reduce(And), plan)
    }
  }
}

/**
 * Combines all adjacent [[Union]] operators into a single [[Union]].
 */
object CombineUnions extends Rule[LogicalPlan] {
  import CollapseProject.{buildCleanedProjectList, canCollapseExpressions}
  import PushProjectionThroughUnion.pushProjectionThroughUnion

  def apply(plan: LogicalPlan): LogicalPlan = plan.transformDownWithPruning(
    _.containsAnyPattern(UNION, DISTINCT_LIKE), ruleId) {
    case u: Union => flattenUnion(u, false)
    case Distinct(u: Union) => Distinct(flattenUnion(u, true))
    // Only handle distinct-like 'Deduplicate', where the keys == output
    case Deduplicate(keys: Seq[Attribute], u: Union) if AttributeSet(keys) == u.outputSet =>
      Deduplicate(keys, flattenUnion(u, true))
  }

  private def flattenUnion(union: Union, flattenDistinct: Boolean): Union = {
    val topByName = union.byName
    val topAllowMissingCol = union.allowMissingCol

    val stack = mutable.Stack[LogicalPlan](union)
    val flattened = mutable.ArrayBuffer.empty[LogicalPlan]
    // Note that we should only flatten the unions with same byName and allowMissingCol.
    // Although we do `UnionCoercion` at analysis phase, we manually run `CombineUnions`
    // in some places like `Dataset.union`. Flattening unions with different resolution
    // rules (by position and by name) could cause incorrect results.
    while (stack.nonEmpty) {
      stack.pop() match {
        case p1 @ Project(_, p2: Project)
            if canCollapseExpressions(p1.projectList, p2.projectList, alwaysInline = false) =>
          val newProjectList = buildCleanedProjectList(p1.projectList, p2.projectList)
          stack.pushAll(Seq(p2.copy(projectList = newProjectList)))
        case Distinct(Union(children, byName, allowMissingCol))
            if flattenDistinct && byName == topByName && allowMissingCol == topAllowMissingCol =>
          stack.pushAll(children.reverse)
        // Only handle distinct-like 'Deduplicate', where the keys == output
        case Deduplicate(keys: Seq[Attribute], u: Union)
            if flattenDistinct && u.byName == topByName &&
              u.allowMissingCol == topAllowMissingCol && AttributeSet(keys) == u.outputSet =>
          stack.pushAll(u.children.reverse)
        case Union(children, byName, allowMissingCol)
            if byName == topByName && allowMissingCol == topAllowMissingCol =>
          stack.pushAll(children.reverse)
        // Push down projection through Union and then push pushed plan to Stack if
        // there is a Project.
        case Project(projectList, Distinct(u @ Union(children, byName, allowMissingCol)))
            if projectList.forall(_.deterministic) && children.nonEmpty &&
              flattenDistinct && byName == topByName && allowMissingCol == topAllowMissingCol =>
          stack.pushAll(pushProjectionThroughUnion(projectList, u).reverse)
        case Project(projectList, Deduplicate(keys: Seq[Attribute], u: Union))
            if projectList.forall(_.deterministic) && flattenDistinct && u.byName == topByName &&
              u.allowMissingCol == topAllowMissingCol && AttributeSet(keys) == u.outputSet =>
          stack.pushAll(pushProjectionThroughUnion(projectList, u).reverse)
        case Project(projectList, u @ Union(children, byName, allowMissingCol))
            if projectList.forall(_.deterministic) && children.nonEmpty &&
              byName == topByName && allowMissingCol == topAllowMissingCol =>
          stack.pushAll(pushProjectionThroughUnion(projectList, u).reverse)
        case child =>
          flattened += child
      }
    }
    union.copy(children = flattened.toSeq)
  }
}

/**
 * Combines two adjacent [[Filter]] operators into one, merging the non-redundant conditions into
 * one conjunctive predicate.
 */
object CombineFilters extends Rule[LogicalPlan] with PredicateHelper {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(FILTER), ruleId)(applyLocally)

  val applyLocally: PartialFunction[LogicalPlan, LogicalPlan] = {
    // The query execution/optimization does not guarantee the expressions are evaluated in order.
    // We only can combine them if and only if both are deterministic.
    case Filter(fc, nf @ Filter(nc, grandChild)) if nc.deterministic =>
      val (combineCandidates, nonDeterministic) =
        splitConjunctivePredicates(fc).partition(_.deterministic)
      val mergedFilter = (ExpressionSet(combineCandidates) --
        ExpressionSet(splitConjunctivePredicates(nc))).reduceOption(And) match {
        case Some(ac) =>
          Filter(And(nc, ac), grandChild)
        case None =>
          nf
      }
      nonDeterministic.reduceOption(And).map(c => Filter(c, mergedFilter)).getOrElse(mergedFilter)
  }
}

/**
 * Removes Sort operations if they don't affect the final output ordering.
 * Note that changes in the final output ordering may affect the file size (SPARK-32318).
 * This rule handles the following cases:
 * 1) if the sort order is empty or the sort order does not have any reference
 * 2) if the Sort operator is a local sort and the child is already sorted
 * 3) if there is another Sort operator separated by 0...n Project, Filter, Repartition or
 *    RepartitionByExpression, RebalancePartitions (with deterministic expressions) operators
 * 4) if the Sort operator is within Join separated by 0...n Project, Filter, Repartition or
 *    RepartitionByExpression, RebalancePartitions (with deterministic expressions) operators only
 *    and the Join condition is deterministic
 * 5) if the Sort operator is within GroupBy separated by 0...n Project, Filter, Repartition or
 *    RepartitionByExpression, RebalancePartitions (with deterministic expressions) operators only
 *    and the aggregate function is order irrelevant
 */
object EliminateSorts extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(SORT))(applyLocally)

  private val applyLocally: PartialFunction[LogicalPlan, LogicalPlan] = {
    case s @ Sort(orders, _, child) if orders.isEmpty || orders.exists(_.child.foldable) =>
      val newOrders = orders.filterNot(_.child.foldable)
      if (newOrders.isEmpty) {
        applyLocally.lift(child).getOrElse(child)
      } else {
        s.copy(order = newOrders)
      }
    case Sort(orders, false, child) if SortOrder.orderingSatisfies(child.outputOrdering, orders) =>
      applyLocally.lift(child).getOrElse(child)
    case s @ Sort(_, _, child) => s.copy(child = recursiveRemoveSort(child))
    case j @ Join(originLeft, originRight, _, cond, _) if cond.forall(_.deterministic) =>
      j.copy(left = recursiveRemoveSort(originLeft), right = recursiveRemoveSort(originRight))
    case g @ Aggregate(_, aggs, originChild) if isOrderIrrelevantAggs(aggs) =>
      g.copy(child = recursiveRemoveSort(originChild))
  }

  private def recursiveRemoveSort(plan: LogicalPlan): LogicalPlan = {
    if (!plan.containsPattern(SORT)) {
      return plan
    }
    plan match {
      case Sort(_, _, child) => recursiveRemoveSort(child)
      case other if canEliminateSort(other) =>
        other.withNewChildren(other.children.map(recursiveRemoveSort))
      case _ => plan
    }
  }

  private def canEliminateSort(plan: LogicalPlan): Boolean = plan match {
    case p: Project => p.projectList.forall(_.deterministic)
    case f: Filter => f.condition.deterministic
    case r: RepartitionByExpression => r.partitionExpressions.forall(_.deterministic)
    case r: RebalancePartitions => r.partitionExpressions.forall(_.deterministic)
    case _: Repartition => true
    case _ => false
  }

  private def isOrderIrrelevantAggs(aggs: Seq[NamedExpression]): Boolean = {
    def isOrderIrrelevantAggFunction(func: AggregateFunction): Boolean = func match {
      case _: Min | _: Max | _: Count | _: BitAggregate => true
      // Arithmetic operations for floating-point values are order-sensitive
      // (they are not associative).
      case _: Sum | _: Average | _: CentralMomentAgg =>
        !Seq(FloatType, DoubleType).exists(_.sameType(func.children.head.dataType))
      case _ => false
    }

    def checkValidAggregateExpression(expr: Expression): Boolean = expr match {
      case _: AttributeReference => true
      case ae: AggregateExpression => isOrderIrrelevantAggFunction(ae.aggregateFunction)
      case _: UserDefinedExpression => false
      case e => e.children.forall(checkValidAggregateExpression)
    }

    aggs.forall(checkValidAggregateExpression)
  }
}

/**
 * Removes filters that can be evaluated trivially.  This can be done through the following ways:
 * 1) by eliding the filter for cases where it will always evaluate to `true`.
 * 2) by substituting a dummy empty relation when the filter will always evaluate to `false`.
 * 3) by eliminating the always-true conditions given the constraints on the child's output.
 */
object PruneFilters extends Rule[LogicalPlan] with PredicateHelper {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(FILTER), ruleId) {
    // If the filter condition always evaluate to true, remove the filter.
    case Filter(Literal(true, BooleanType), child) => child
    // If the filter condition always evaluate to null or false,
    // replace the input with an empty relation.
    case Filter(Literal(null, _), child) =>
      LocalRelation(child.output, data = Seq.empty, isStreaming = plan.isStreaming)
    case Filter(Literal(false, BooleanType), child) =>
      LocalRelation(child.output, data = Seq.empty, isStreaming = plan.isStreaming)
    // If any deterministic condition is guaranteed to be true given the constraints on the child's
    // output, remove the condition
    case f @ Filter(fc, p: LogicalPlan) =>
      val (prunedPredicates, remainingPredicates) =
        splitConjunctivePredicates(fc).partition { cond =>
          cond.deterministic && p.constraints.contains(cond)
        }
      if (prunedPredicates.isEmpty) {
        f
      } else if (remainingPredicates.isEmpty) {
        p
      } else {
        val newCond = remainingPredicates.reduce(And)
        Filter(newCond, p)
      }
  }
}

/**
 * The unified version for predicate pushdown of normal operators and joins.
 * This rule improves performance of predicate pushdown for cascading joins such as:
 *  Filter-Join-Join-Join. Most predicates can be pushed down in a single pass.
 */
object PushDownPredicates extends Rule[LogicalPlan] with PredicateHelper {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsAnyPattern(FILTER, JOIN)) {
    CombineFilters.applyLocally
      .orElse(PushPredicateThroughNonJoin.applyLocally)
      .orElse(PushPredicateThroughJoin.applyLocally)
  }
}

/**
 * Pushes [[Filter]] operators through many operators iff:
 * 1) the operator is deterministic
 * 2) the predicate is deterministic and the operator will not change any of rows.
 *
 * This heuristic is valid assuming the expression evaluation cost is minimal.
 */
object PushPredicateThroughNonJoin extends Rule[LogicalPlan] with PredicateHelper {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform applyLocally

  val applyLocally: PartialFunction[LogicalPlan, LogicalPlan] = {
    // SPARK-13473: We can't push the predicate down when the underlying projection output non-
    // deterministic field(s).  Non-deterministic expressions are essentially stateful. This
    // implies that, for a given input row, the output are determined by the expression's initial
    // state and all the input rows processed before. In another word, the order of input rows
    // matters for non-deterministic expressions, while pushing down predicates changes the order.
    // This also applies to Aggregate.
    case Filter(condition, project @ Project(fields, grandChild))
      if fields.forall(_.deterministic) && canPushThroughCondition(grandChild, condition) =>
      val aliasMap = getAliasMap(project)
      project.copy(child = Filter(replaceAlias(condition, aliasMap), grandChild))

    case filter @ Filter(condition, aggregate: Aggregate)
      if aggregate.aggregateExpressions.forall(_.deterministic)
        && aggregate.groupingExpressions.nonEmpty =>
      val aliasMap = getAliasMap(aggregate)

      // For each filter, expand the alias and check if the filter can be evaluated using
      // attributes produced by the aggregate operator's child operator.
      val (candidates, nonDeterministic) =
        splitConjunctivePredicates(condition).partition(_.deterministic)

      val (pushDown, rest) = candidates.partition { cond =>
        val replaced = replaceAlias(cond, aliasMap)
        cond.references.nonEmpty && replaced.references.subsetOf(aggregate.child.outputSet)
      }

      val stayUp = rest ++ nonDeterministic

      if (pushDown.nonEmpty) {
        val pushDownPredicate = pushDown.reduce(And)
        val replaced = replaceAlias(pushDownPredicate, aliasMap)
        val newAggregate = aggregate.copy(child = Filter(replaced, aggregate.child))
        // If there is no more filter to stay up, just eliminate the filter.
        // Otherwise, create "Filter(stayUp) <- Aggregate <- Filter(pushDownPredicate)".
        if (stayUp.isEmpty) newAggregate else Filter(stayUp.reduce(And), newAggregate)
      } else {
        filter
      }

    // Push [[Filter]] operators through [[Window]] operators. Parts of the predicate that can be
    // pushed beneath must satisfy the following conditions:
    // 1. All the expressions are part of window partitioning key. The expressions can be compound.
    // 2. Deterministic.
    // 3. Placed before any non-deterministic predicates.
    case filter @ Filter(condition, w: Window)
      if w.partitionSpec.forall(_.isInstanceOf[AttributeReference]) =>
      val partitionAttrs = AttributeSet(w.partitionSpec.flatMap(_.references))

      val (candidates, nonDeterministic) =
        splitConjunctivePredicates(condition).partition(_.deterministic)

      val (pushDown, rest) = candidates.partition { cond =>
        cond.references.subsetOf(partitionAttrs)
      }

      val stayUp = rest ++ nonDeterministic

      if (pushDown.nonEmpty) {
        val pushDownPredicate = pushDown.reduce(And)
        val newWindow = w.copy(child = Filter(pushDownPredicate, w.child))
        if (stayUp.isEmpty) newWindow else Filter(stayUp.reduce(And), newWindow)
      } else {
        filter
      }

    case filter @ Filter(condition, union: Union) =>
      // Union could change the rows, so non-deterministic predicate can't be pushed down
      val (pushDown, stayUp) = splitConjunctivePredicates(condition).partition(_.deterministic)

      if (pushDown.nonEmpty) {
        val pushDownCond = pushDown.reduceLeft(And)
        val output = union.output
        val newGrandChildren = union.children.map { grandchild =>
          val newCond = pushDownCond transform {
            case e if output.exists(_.semanticEquals(e)) =>
              grandchild.output(output.indexWhere(_.semanticEquals(e)))
          }
          assert(newCond.references.subsetOf(grandchild.outputSet))
          Filter(newCond, grandchild)
        }
        val newUnion = union.withNewChildren(newGrandChildren)
        if (stayUp.nonEmpty) {
          Filter(stayUp.reduceLeft(And), newUnion)
        } else {
          newUnion
        }
      } else {
        filter
      }

    case filter @ Filter(condition, watermark: EventTimeWatermark) =>
      val (pushDown, stayUp) = splitConjunctivePredicates(condition).partition { p =>
        p.deterministic && !p.references.contains(watermark.eventTime)
      }

      if (pushDown.nonEmpty) {
        val pushDownPredicate = pushDown.reduceLeft(And)
        val newWatermark = watermark.copy(child = Filter(pushDownPredicate, watermark.child))
        // If there is no more filter to stay up, just eliminate the filter.
        // Otherwise, create "Filter(stayUp) <- watermark <- Filter(pushDownPredicate)".
        if (stayUp.isEmpty) newWatermark else Filter(stayUp.reduceLeft(And), newWatermark)
      } else {
        filter
      }

    case filter @ Filter(_, u: UnaryNode)
        if canPushThrough(u) && u.expressions.forall(_.deterministic) =>
      pushDownPredicate(filter, u.child) { predicate =>
        u.withNewChildren(Seq(Filter(predicate, u.child)))
      }
  }

  def canPushThrough(p: UnaryNode): Boolean = p match {
    // Note that some operators (e.g. project, aggregate, union) are being handled separately
    // (earlier in this rule).
    case _: AppendColumns => true
    case _: Distinct => true
    case _: Generate => true
    case _: Pivot => true
    case _: RepartitionByExpression => true
    case _: Repartition => true
    case _: RebalancePartitions => true
    case _: ScriptTransformation => true
    case _: Sort => true
    case _: BatchEvalPython => true
    case _: ArrowEvalPython => true
    case _: Expand => true
    case _ => false
  }

  private def pushDownPredicate(
      filter: Filter,
      grandchild: LogicalPlan)(insertFilter: Expression => LogicalPlan): LogicalPlan = {
    // Only push down the predicates that is deterministic and all the referenced attributes
    // come from grandchild.
    // TODO: non-deterministic predicates could be pushed through some operators that do not change
    // the rows.
    val (candidates, nonDeterministic) =
      splitConjunctivePredicates(filter.condition).partition(_.deterministic)

    val (pushDown, rest) = candidates.partition { cond =>
      cond.references.subsetOf(grandchild.outputSet)
    }

    val stayUp = rest ++ nonDeterministic

    if (pushDown.nonEmpty) {
      val newChild = insertFilter(pushDown.reduceLeft(And))
      if (stayUp.nonEmpty) {
        Filter(stayUp.reduceLeft(And), newChild)
      } else {
        newChild
      }
    } else {
      filter
    }
  }

  /**
   * Check if we can safely push a filter through a projection, by making sure that predicate
   * subqueries in the condition do not contain the same attributes as the plan they are moved
   * into. This can happen when the plan and predicate subquery have the same source.
   */
  private def canPushThroughCondition(plan: LogicalPlan, condition: Expression): Boolean = {
    val attributes = plan.outputSet
    !condition.exists {
      case s: SubqueryExpression => s.plan.outputSet.intersect(attributes).nonEmpty
      case _ => false
    }
  }
}

/**
 * Pushes down [[Filter]] operators where the `condition` can be
 * evaluated using only the attributes of the left or right side of a join.  Other
 * [[Filter]] conditions are moved into the `condition` of the [[Join]].
 *
 * And also pushes down the join filter, where the `condition` can be evaluated using only the
 * attributes of the left or right side of sub query when applicable.
 *
 * Check https://cwiki.apache.org/confluence/display/Hive/OuterJoinBehavior for more details
 */
object PushPredicateThroughJoin extends Rule[LogicalPlan] with PredicateHelper {
  /**
   * Splits join condition expressions or filter predicates (on a given join's output) into three
   * categories based on the attributes required to evaluate them. Note that we explicitly exclude
   * non-deterministic (i.e., stateful) condition expressions in canEvaluateInLeft or
   * canEvaluateInRight to prevent pushing these predicates on either side of the join.
   *
   * @return (canEvaluateInLeft, canEvaluateInRight, haveToEvaluateInBoth)
   */
  private def split(condition: Seq[Expression], left: LogicalPlan, right: LogicalPlan) = {
    val (pushDownCandidates, nonDeterministic) = condition.partition(_.deterministic)
    val (leftEvaluateCondition, rest) =
      pushDownCandidates.partition(_.references.subsetOf(left.outputSet))
    val (rightEvaluateCondition, commonCondition) =
        rest.partition(expr => expr.references.subsetOf(right.outputSet))

    (leftEvaluateCondition, rightEvaluateCondition, commonCondition ++ nonDeterministic)
  }

  private def canPushThrough(joinType: JoinType): Boolean = joinType match {
    case _: InnerLike | LeftSemi | RightOuter | LeftOuter | LeftAnti | ExistenceJoin(_) => true
    case _ => false
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform applyLocally

  val applyLocally: PartialFunction[LogicalPlan, LogicalPlan] = {
    // push the where condition down into join filter
    case f @ Filter(filterCondition, Join(left, right, joinType, joinCondition, hint))
        if canPushThrough(joinType) =>
      val (leftFilterConditions, rightFilterConditions, commonFilterCondition) =
        split(splitConjunctivePredicates(filterCondition), left, right)
      joinType match {
        case _: InnerLike =>
          // push down the single side `where` condition into respective sides
          val newLeft = leftFilterConditions.
            reduceLeftOption(And).map(Filter(_, left)).getOrElse(left)
          val newRight = rightFilterConditions.
            reduceLeftOption(And).map(Filter(_, right)).getOrElse(right)
          val (newJoinConditions, others) =
            commonFilterCondition.partition(canEvaluateWithinJoin)
          val newJoinCond = (newJoinConditions ++ joinCondition).reduceLeftOption(And)

          val join = Join(newLeft, newRight, joinType, newJoinCond, hint)
          if (others.nonEmpty) {
            Filter(others.reduceLeft(And), join)
          } else {
            join
          }
        case RightOuter =>
          // push down the right side only `where` condition
          val newLeft = left
          val newRight = rightFilterConditions.
            reduceLeftOption(And).map(Filter(_, right)).getOrElse(right)
          val newJoinCond = joinCondition
          val newJoin = Join(newLeft, newRight, RightOuter, newJoinCond, hint)

          (leftFilterConditions ++ commonFilterCondition).
            reduceLeftOption(And).map(Filter(_, newJoin)).getOrElse(newJoin)
        case LeftOuter | LeftExistence(_) =>
          // push down the left side only `where` condition
          val newLeft = leftFilterConditions.
            reduceLeftOption(And).map(Filter(_, left)).getOrElse(left)
          val newRight = right
          val newJoinCond = joinCondition
          val newJoin = Join(newLeft, newRight, joinType, newJoinCond, hint)

          (rightFilterConditions ++ commonFilterCondition).
            reduceLeftOption(And).map(Filter(_, newJoin)).getOrElse(newJoin)

        case other =>
          throw new IllegalStateException(s"Unexpected join type: $other")
      }

    // push down the join filter into sub query scanning if applicable
    case j @ Join(left, right, joinType, joinCondition, hint) if canPushThrough(joinType) =>
      val (leftJoinConditions, rightJoinConditions, commonJoinCondition) =
        split(joinCondition.map(splitConjunctivePredicates).getOrElse(Nil), left, right)

      joinType match {
        case _: InnerLike | LeftSemi =>
          // push down the single side only join filter for both sides sub queries
          val newLeft = leftJoinConditions.
            reduceLeftOption(And).map(Filter(_, left)).getOrElse(left)
          val newRight = rightJoinConditions.
            reduceLeftOption(And).map(Filter(_, right)).getOrElse(right)
          val newJoinCond = commonJoinCondition.reduceLeftOption(And)

          Join(newLeft, newRight, joinType, newJoinCond, hint)
        case RightOuter =>
          // push down the left side only join filter for left side sub query
          val newLeft = leftJoinConditions.
            reduceLeftOption(And).map(Filter(_, left)).getOrElse(left)
          val newRight = right
          val newJoinCond = (rightJoinConditions ++ commonJoinCondition).reduceLeftOption(And)

          Join(newLeft, newRight, RightOuter, newJoinCond, hint)
        case LeftOuter | LeftAnti | ExistenceJoin(_) =>
          // push down the right side only join filter for right sub query
          val newLeft = left
          val newRight = rightJoinConditions.
            reduceLeftOption(And).map(Filter(_, right)).getOrElse(right)
          val newJoinCond = (leftJoinConditions ++ commonJoinCondition).reduceLeftOption(And)

          Join(newLeft, newRight, joinType, newJoinCond, hint)

        case other =>
          throw new IllegalStateException(s"Unexpected join type: $other")
      }
  }
}

/**
 * This rule is applied by both normal and AQE Optimizer, and optimizes Limit operators by:
 * 1. Eliminate [[Limit]]/[[GlobalLimit]] operators if it's child max row <= limit.
 * 2. Combines two adjacent [[Limit]] operators into one, merging the
 *    expressions into one single expression.
 */
object EliminateLimits extends Rule[LogicalPlan] {
  private def canEliminate(limitExpr: Expression, child: LogicalPlan): Boolean = {
    limitExpr.foldable && child.maxRows.exists { _ <= limitExpr.eval().asInstanceOf[Int] }
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan.transformDownWithPruning(
    _.containsPattern(LIMIT), ruleId) {
    case Limit(l, child) if canEliminate(l, child) =>
      child
    case GlobalLimit(l, child) if canEliminate(l, child) =>
      child

    case GlobalLimit(le, GlobalLimit(ne, grandChild)) =>
      GlobalLimit(Literal(Least(Seq(ne, le)).eval().asInstanceOf[Int]), grandChild)
    case LocalLimit(le, LocalLimit(ne, grandChild)) =>
      LocalLimit(Literal(Least(Seq(ne, le)).eval().asInstanceOf[Int]), grandChild)
    case Limit(le, Limit(ne, grandChild)) =>
      Limit(Literal(Least(Seq(ne, le)).eval().asInstanceOf[Int]), grandChild)
  }
}

/**
 * Rewrite [[Offset]] as [[GlobalLimitAndOffset]] or [[LocalLimit]],
 * merging the expressions into one single expression. See [[Limit]] for more information
 * about the difference between [[LocalLimit]] and [[GlobalLimit]].
 */
object RewriteOffsets extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case GlobalLimit(le, Offset(oe, grandChild)) =>
      GlobalLimitAndOffset(le, oe, grandChild)
    case localLimit @ LocalLimit(le, Offset(oe, grandChild)) =>
      val offset = oe.eval().asInstanceOf[Int]
      if (offset == 0) {
        localLimit.withNewChildren(Seq(grandChild))
      } else {
        Offset(oe, LocalLimit(Add(le, oe), grandChild))
      }
  }
}

/**
 * Check if there any cartesian products between joins of any type in the optimized plan tree.
 * Throw an error if a cartesian product is found without an explicit cross join specified.
 * This rule is effectively disabled if the CROSS_JOINS_ENABLED flag is true.
 *
 * This rule must be run AFTER the ReorderJoin rule since the join conditions for each join must be
 * collected before checking if it is a cartesian product. If you have
 * SELECT * from R, S where R.r = S.s,
 * the join between R and S is not a cartesian product and therefore should be allowed.
 * The predicate R.r = S.s is not recognized as a join condition until the ReorderJoin rule.
 *
 * This rule must be run AFTER the batch "LocalRelation", since a join with empty relation should
 * not be a cartesian product.
 */
object CheckCartesianProducts extends Rule[LogicalPlan] with PredicateHelper {
  /**
   * Check if a join is a cartesian product. Returns true if
   * there are no join conditions involving references from both left and right.
   */
  def isCartesianProduct(join: Join): Boolean = {
    val conditions = join.condition.map(splitConjunctivePredicates).getOrElse(Nil)

    conditions match {
      case Seq(Literal.FalseLiteral) | Seq(Literal(null, BooleanType)) => false
      case _ => !conditions.map(_.references).exists(refs =>
        refs.exists(join.left.outputSet.contains) && refs.exists(join.right.outputSet.contains))
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan =
    if (conf.crossJoinEnabled) {
      plan
    } else plan.transformWithPruning(_.containsAnyPattern(INNER_LIKE_JOIN, OUTER_JOIN))  {
      case j @ Join(left, right, Inner | LeftOuter | RightOuter | FullOuter, _, _)
        if isCartesianProduct(j) =>
          throw QueryCompilationErrors.joinConditionMissingOrTrivialError(j, left, right)
    }
}

/**
 * Speeds up aggregates on fixed-precision decimals by executing them on unscaled Long values.
 *
 * This uses the same rules for increasing the precision and scale of the output as
 * [[org.apache.spark.sql.catalyst.analysis.DecimalPrecision]].
 */
object DecimalAggregates extends Rule[LogicalPlan] {
  import Decimal.MAX_LONG_DIGITS

  /** Maximum number of decimal digits representable precisely in a Double */
  private val MAX_DOUBLE_DIGITS = 15

  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsAnyPattern(SUM, AVERAGE), ruleId) {
    case q: LogicalPlan => q.transformExpressionsDownWithPruning(
      _.containsAnyPattern(SUM, AVERAGE), ruleId) {
      case we @ WindowExpression(ae @ AggregateExpression(af, _, _, _, _), _) => af match {
        case Sum(e @ DecimalType.Expression(prec, scale), _) if prec + 10 <= MAX_LONG_DIGITS =>
          MakeDecimal(we.copy(windowFunction = ae.copy(aggregateFunction = Sum(UnscaledValue(e)))),
            prec + 10, scale)

        case Average(e @ DecimalType.Expression(prec, scale), _) if prec + 4 <= MAX_DOUBLE_DIGITS =>
          val newAggExpr =
            we.copy(windowFunction = ae.copy(aggregateFunction = Average(UnscaledValue(e))))
          Cast(
            Divide(newAggExpr, Literal.create(math.pow(10.0, scale), DoubleType)),
            DecimalType(prec + 4, scale + 4), Option(conf.sessionLocalTimeZone))

        case _ => we
      }
      case ae @ AggregateExpression(af, _, _, _, _) => af match {
        case Sum(e @ DecimalType.Expression(prec, scale), _) if prec + 10 <= MAX_LONG_DIGITS =>
          MakeDecimal(ae.copy(aggregateFunction = Sum(UnscaledValue(e))), prec + 10, scale)

        case Average(e @ DecimalType.Expression(prec, scale), _) if prec + 4 <= MAX_DOUBLE_DIGITS =>
          val newAggExpr = ae.copy(aggregateFunction = Average(UnscaledValue(e)))
          Cast(
            Divide(newAggExpr, Literal.create(math.pow(10.0, scale), DoubleType)),
            DecimalType(prec + 4, scale + 4), Option(conf.sessionLocalTimeZone))

        case _ => ae
      }
    }
  }
}

/**
 * Converts local operations (i.e. ones that don't require data exchange) on `LocalRelation` to
 * another `LocalRelation`.
 */
object ConvertToLocalRelation extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(LOCAL_RELATION), ruleId) {
    case Project(projectList, LocalRelation(output, data, isStreaming))
        if !projectList.exists(hasUnevaluableExpr) =>
      val projection = new InterpretedMutableProjection(projectList, output)
      projection.initialize(0)
      LocalRelation(projectList.map(_.toAttribute), data.map(projection(_).copy()), isStreaming)

    case Limit(IntegerLiteral(limit), LocalRelation(output, data, isStreaming)) =>
      LocalRelation(output, data.take(limit), isStreaming)

    case Filter(condition, LocalRelation(output, data, isStreaming))
        if !hasUnevaluableExpr(condition) =>
      val predicate = Predicate.create(condition, output)
      predicate.initialize(0)
      LocalRelation(output, data.filter(row => predicate.eval(row)), isStreaming)
  }

  private def hasUnevaluableExpr(expr: Expression): Boolean = {
    expr.exists(e => e.isInstanceOf[Unevaluable] && !e.isInstanceOf[AttributeReference])
  }
}

/**
 * Replaces logical [[Distinct]] operator with an [[Aggregate]] operator.
 * {{{
 *   SELECT DISTINCT f1, f2 FROM t  ==>  SELECT f1, f2 FROM t GROUP BY f1, f2
 * }}}
 */
object ReplaceDistinctWithAggregate extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(DISTINCT_LIKE), ruleId) {
    case Distinct(child) => Aggregate(child.output, child.output, child)
  }
}

/**
 * Replaces logical [[Deduplicate]] operator with an [[Aggregate]] operator.
 */
object ReplaceDeduplicateWithAggregate extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan transformUpWithNewOutput {
    case d @ Deduplicate(keys, child) if !child.isStreaming =>
      val keyExprIds = keys.map(_.exprId)
      val aggCols = child.output.map { attr =>
        if (keyExprIds.contains(attr.exprId)) {
          attr
        } else {
          Alias(new First(attr).toAggregateExpression(), attr.name)()
        }
      }
      // SPARK-22951: Physical aggregate operators distinguishes global aggregation and grouping
      // aggregations by checking the number of grouping keys. The key difference here is that a
      // global aggregation always returns at least one row even if there are no input rows. Here
      // we append a literal when the grouping key list is empty so that the result aggregate
      // operator is properly treated as a grouping aggregation.
      val nonemptyKeys = if (keys.isEmpty) Literal(1) :: Nil else keys
      val newAgg = Aggregate(nonemptyKeys, aggCols, child)
      val attrMapping = d.output.zip(newAgg.output)
      newAgg -> attrMapping
  }
}

/**
 * Replaces logical [[Intersect]] operator with a left-semi [[Join]] operator.
 * {{{
 *   SELECT a1, a2 FROM Tab1 INTERSECT SELECT b1, b2 FROM Tab2
 *   ==>  SELECT DISTINCT a1, a2 FROM Tab1 LEFT SEMI JOIN Tab2 ON a1<=>b1 AND a2<=>b2
 * }}}
 *
 * Note:
 * 1. This rule is only applicable to INTERSECT DISTINCT. Do not use it for INTERSECT ALL.
 * 2. This rule has to be done after de-duplicating the attributes; otherwise, the generated
 *    join conditions will be incorrect.
 */
object ReplaceIntersectWithSemiJoin extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(INTERSECT), ruleId) {
    case Intersect(left, right, false) =>
      assert(left.output.size == right.output.size)
      val joinCond = left.output.zip(right.output).map { case (l, r) => EqualNullSafe(l, r) }
      Distinct(Join(left, right, LeftSemi, joinCond.reduceLeftOption(And), JoinHint.NONE))
  }
}

/**
 * Replaces logical [[Except]] operator with a left-anti [[Join]] operator.
 * {{{
 *   SELECT a1, a2 FROM Tab1 EXCEPT SELECT b1, b2 FROM Tab2
 *   ==>  SELECT DISTINCT a1, a2 FROM Tab1 LEFT ANTI JOIN Tab2 ON a1<=>b1 AND a2<=>b2
 * }}}
 *
 * Note:
 * 1. This rule is only applicable to EXCEPT DISTINCT. Do not use it for EXCEPT ALL.
 * 2. This rule has to be done after de-duplicating the attributes; otherwise, the generated
 *    join conditions will be incorrect.
 */
object ReplaceExceptWithAntiJoin extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(EXCEPT), ruleId) {
    case Except(left, right, false) =>
      assert(left.output.size == right.output.size)
      val joinCond = left.output.zip(right.output).map { case (l, r) => EqualNullSafe(l, r) }
      Distinct(Join(left, right, LeftAnti, joinCond.reduceLeftOption(And), JoinHint.NONE))
  }
}

/**
 * Replaces logical [[Except]] operator using a combination of Union, Aggregate
 * and Generate operator.
 *
 * Input Query :
 * {{{
 *    SELECT c1 FROM ut1 EXCEPT ALL SELECT c1 FROM ut2
 * }}}
 *
 * Rewritten Query:
 * {{{
 *   SELECT c1
 *   FROM (
 *     SELECT replicate_rows(sum_val, c1)
 *       FROM (
 *         SELECT c1, sum_val
 *           FROM (
 *             SELECT c1, sum(vcol) AS sum_val
 *               FROM (
 *                 SELECT 1L as vcol, c1 FROM ut1
 *                 UNION ALL
 *                 SELECT -1L as vcol, c1 FROM ut2
 *              ) AS union_all
 *            GROUP BY union_all.c1
 *          )
 *        WHERE sum_val > 0
 *       )
 *   )
 * }}}
 */

object RewriteExceptAll extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(EXCEPT), ruleId) {
    case Except(left, right, true) =>
      assert(left.output.size == right.output.size)

      val newColumnLeft = Alias(Literal(1L), "vcol")()
      val newColumnRight = Alias(Literal(-1L), "vcol")()
      val modifiedLeftPlan = Project(Seq(newColumnLeft) ++ left.output, left)
      val modifiedRightPlan = Project(Seq(newColumnRight) ++ right.output, right)
      val unionPlan = Union(modifiedLeftPlan, modifiedRightPlan)
      val aggSumCol =
        Alias(AggregateExpression(Sum(unionPlan.output.head.toAttribute), Complete, false), "sum")()
      val aggOutputColumns = left.output ++ Seq(aggSumCol)
      val aggregatePlan = Aggregate(left.output, aggOutputColumns, unionPlan)
      val filteredAggPlan = Filter(GreaterThan(aggSumCol.toAttribute, Literal(0L)), aggregatePlan)
      val genRowPlan = Generate(
        ReplicateRows(Seq(aggSumCol.toAttribute) ++ left.output),
        unrequiredChildIndex = Nil,
        outer = false,
        qualifier = None,
        left.output,
        filteredAggPlan
      )
      Project(left.output, genRowPlan)
  }
}

/**
 * Replaces logical [[Intersect]] operator using a combination of Union, Aggregate
 * and Generate operator.
 *
 * Input Query :
 * {{{
 *    SELECT c1 FROM ut1 INTERSECT ALL SELECT c1 FROM ut2
 * }}}
 *
 * Rewritten Query:
 * {{{
 *   SELECT c1
 *   FROM (
 *        SELECT replicate_row(min_count, c1)
 *        FROM (
 *             SELECT c1, If (vcol1_cnt > vcol2_cnt, vcol2_cnt, vcol1_cnt) AS min_count
 *             FROM (
 *                  SELECT   c1, count(vcol1) as vcol1_cnt, count(vcol2) as vcol2_cnt
 *                  FROM (
 *                       SELECT true as vcol1, null as , c1 FROM ut1
 *                       UNION ALL
 *                       SELECT null as vcol1, true as vcol2, c1 FROM ut2
 *                       ) AS union_all
 *                  GROUP BY c1
 *                  HAVING vcol1_cnt >= 1 AND vcol2_cnt >= 1
 *                  )
 *             )
 *         )
 * }}}
 */
object RewriteIntersectAll extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(INTERSECT), ruleId) {
    case Intersect(left, right, true) =>
      assert(left.output.size == right.output.size)

      val trueVcol1 = Alias(Literal(true), "vcol1")()
      val nullVcol1 = Alias(Literal(null, BooleanType), "vcol1")()

      val trueVcol2 = Alias(Literal(true), "vcol2")()
      val nullVcol2 = Alias(Literal(null, BooleanType), "vcol2")()

      // Add a projection on the top of left and right plans to project out
      // the additional virtual columns.
      val leftPlanWithAddedVirtualCols = Project(Seq(trueVcol1, nullVcol2) ++ left.output, left)
      val rightPlanWithAddedVirtualCols = Project(Seq(nullVcol1, trueVcol2) ++ right.output, right)

      val unionPlan = Union(leftPlanWithAddedVirtualCols, rightPlanWithAddedVirtualCols)

      // Expressions to compute count and minimum of both the counts.
      val vCol1AggrExpr =
        Alias(AggregateExpression(Count(unionPlan.output(0)), Complete, false), "vcol1_count")()
      val vCol2AggrExpr =
        Alias(AggregateExpression(Count(unionPlan.output(1)), Complete, false), "vcol2_count")()
      val ifExpression = Alias(If(
        GreaterThan(vCol1AggrExpr.toAttribute, vCol2AggrExpr.toAttribute),
        vCol2AggrExpr.toAttribute,
        vCol1AggrExpr.toAttribute
      ), "min_count")()

      val aggregatePlan = Aggregate(left.output,
        Seq(vCol1AggrExpr, vCol2AggrExpr) ++ left.output, unionPlan)
      val filterPlan = Filter(And(GreaterThanOrEqual(vCol1AggrExpr.toAttribute, Literal(1L)),
        GreaterThanOrEqual(vCol2AggrExpr.toAttribute, Literal(1L))), aggregatePlan)
      val projectMinPlan = Project(left.output ++ Seq(ifExpression), filterPlan)

      // Apply the replicator to replicate rows based on min_count
      val genRowPlan = Generate(
        ReplicateRows(Seq(ifExpression.toAttribute) ++ left.output),
        unrequiredChildIndex = Nil,
        outer = false,
        qualifier = None,
        left.output,
        projectMinPlan
      )
      Project(left.output, genRowPlan)
  }
}

/**
 * Removes literals from group expressions in [[Aggregate]], as they have no effect to the result
 * but only makes the grouping key bigger.
 */
object RemoveLiteralFromGroupExpressions extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(AGGREGATE), ruleId) {
    case a @ Aggregate(grouping, _, _) if grouping.nonEmpty =>
      val newGrouping = grouping.filter(!_.foldable)
      if (newGrouping.nonEmpty) {
        a.copy(groupingExpressions = newGrouping)
      } else {
        // All grouping expressions are literals. We should not drop them all, because this can
        // change the return semantics when the input of the Aggregate is empty (SPARK-17114). We
        // instead replace this by single, easy to hash/sort, literal expression.
        a.copy(groupingExpressions = Seq(Literal(0, IntegerType)))
      }
  }
}

/**
 * Prunes unnecessary fields from a [[Generate]] if it is under a project which does not refer
 * any generated attributes, .e.g., count-like aggregation on an exploded array.
 */
object GenerateOptimization extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformDownWithPruning(
      _.containsAllPatterns(PROJECT, GENERATE), ruleId) {
      case p @ Project(_, g: Generate) if p.references.isEmpty
          && g.generator.isInstanceOf[ExplodeBase] =>
        g.generator.children.head.dataType match {
          case ArrayType(StructType(fields), containsNull) if fields.length > 1 =>
            // Try to pick up smallest field
            val sortedFields = fields.zipWithIndex.sortBy(f => f._1.dataType.defaultSize)
            val extractor = GetArrayStructFields(g.generator.children.head, sortedFields(0)._1,
              sortedFields(0)._2, fields.length, containsNull || sortedFields(0)._1.nullable)

            val rewrittenG = g.transformExpressions {
              case e: ExplodeBase =>
                e.withNewChildren(Seq(extractor))
            }
            // As we change the child of the generator, its output data type must be updated.
            val updatedGeneratorOutput = rewrittenG.generatorOutput
              .zip(rewrittenG.generator.elementSchema.toAttributes)
              .map { case (oldAttr, newAttr) =>
                newAttr.withExprId(oldAttr.exprId).withName(oldAttr.name)
              }
            assert(updatedGeneratorOutput.length == rewrittenG.generatorOutput.length,
              "Updated generator output must have the same length " +
                "with original generator output.")
            val updatedGenerate = rewrittenG.copy(generatorOutput = updatedGeneratorOutput)
            p.withNewChildren(Seq(updatedGenerate))
          case _ => p
        }
    }
}

/**
 * Removes repetition from group expressions in [[Aggregate]], as they have no effect to the result
 * but only makes the grouping key bigger.
 */
object RemoveRepetitionFromGroupExpressions extends Rule[LogicalPlan] {
  def apply(plan: LogicalPlan): LogicalPlan = plan.transformWithPruning(
    _.containsPattern(AGGREGATE), ruleId) {
    case a @ Aggregate(grouping, _, _) if grouping.size > 1 =>
      val newGrouping = ExpressionSet(grouping).toSeq
      if (newGrouping.size == grouping.size) {
        a
      } else {
        a.copy(groupingExpressions = newGrouping)
      }
  }
}

/**
 * Replaces GlobalLimit 0 and LocalLimit 0 nodes (subtree) with empty Local Relation, as they don't
 * return any rows.
 */
object OptimizeLimitZero extends Rule[LogicalPlan] {
  // returns empty Local Relation corresponding to given plan
  private def empty(plan: LogicalPlan) =
    LocalRelation(plan.output, data = Seq.empty, isStreaming = plan.isStreaming)

  def apply(plan: LogicalPlan): LogicalPlan = plan.transformUpWithPruning(
    _.containsAllPatterns(LIMIT, LITERAL)) {
    // Nodes below GlobalLimit or LocalLimit can be pruned if the limit value is zero (0).
    // Any subtree in the logical plan that has GlobalLimit 0 or LocalLimit 0 as its root is
    // semantically equivalent to an empty relation.
    //
    // In such cases, the effects of Limit 0 can be propagated through the Logical Plan by replacing
    // the (Global/Local) Limit subtree with an empty LocalRelation, thereby pruning the subtree
    // below and triggering other optimization rules of PropagateEmptyRelation to propagate the
    // changes up the Logical Plan.
    //
    // Replace Global Limit 0 nodes with empty Local Relation
    case gl @ GlobalLimit(IntegerLiteral(0), _) =>
      empty(gl)

    // Note: For all SQL queries, if a LocalLimit 0 node exists in the Logical Plan, then a
    // GlobalLimit 0 node would also exist. Thus, the above case would be sufficient to handle
    // almost all cases. However, if a user explicitly creates a Logical Plan with LocalLimit 0 node
    // then the following rule will handle that case as well.
    //
    // Replace Local Limit 0 nodes with empty Local Relation
    case ll @ LocalLimit(IntegerLiteral(0), _) =>
      empty(ll)
  }
}
