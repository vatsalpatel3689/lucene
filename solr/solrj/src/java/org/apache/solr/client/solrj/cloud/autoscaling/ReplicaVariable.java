/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.client.solrj.cloud.autoscaling;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.solr.common.util.StrUtils;

class ReplicaVariable extends VariableBase {

  public ReplicaVariable(Type type) {
    super(type);
  }

  static int getRelevantReplicasCount(Policy.Session session, Condition cv, String collection, String shard) {
    PerClauseData.CollectionDetails cd = session.perClauseData.collections.get(collection);
    if (cd != null) {
      if (shard != null && !Policy.ANY.equals(shard)) {
        PerClauseData.ShardDetails sd = cd.shards.get(shard);
        if (sd != null) return sd.replicas.getVal(cv.clause.type).intValue();

      } else {
        int totalReplicasOfInterest = 0;

        for (PerClauseData.ShardDetails sd : cd.shards.values()) {
          totalReplicasOfInterest += sd.replicas.getVal(cv.clause.type).intValue();
        }
        return totalReplicasOfInterest;
      }

    }
    return 0;

   /* Clause clause = cv.getClause();
    for (Row row : session.matrix) {
      Integer perShardCount = row.computeCacheIfAbsent(collection, shard, REPLICASCOUNT, cv.clause, o -> {
        int[] result = new int[1];
        row.forEachReplica(collection, replicaInfo -> {
          if (clause.isMatch(replicaInfo, collection, shard))
            result[0]++;
        });
        return result[0];
      });
      if (perShardCount != null)
        totalReplicasOfInterest += perShardCount;
    }
    return totalReplicasOfInterest;*/
  }

  @Override
  public Object validate(String name, Object val, boolean isRuleVal) {
    return getOperandAdjustedValue(super.validate(name, val, isRuleVal), val);
  }



  @Override
  public Operand getOperand(Operand expected, Object strVal, ComputedType computedType) {
    if (computedType == ComputedType.ALL) return expected;
    return checkForRangeOperand(expected, strVal, computedType);
  }

  static Operand checkForRangeOperand(Operand expected, Object strVal, ComputedType computedType) {
    if (strVal instanceof String) {
      String s = ((String) strVal).trim();
      int hyphenIdx = s.indexOf('-');
      if (hyphenIdx > 0) {
        if (hyphenIdx == s.length() - 1) {
          throw new IllegalArgumentException("bad range input :" + expected);
        }
        if (expected == Operand.EQUAL) return Operand.RANGE_EQUAL;
        if (expected == Operand.NOT_EQUAL) return Operand.RANGE_NOT_EQUAL;
      }

    }

    if (expected == Operand.EQUAL && (computedType != null || !isIntegerEquivalent(strVal))) {
      return Operand.RANGE_EQUAL;
    }
    if (expected == Operand.NOT_EQUAL && (computedType != null || !isIntegerEquivalent(strVal)))
      return Operand.RANGE_NOT_EQUAL;

    return expected;
  }

  @Override
  public String postValidate(Condition condition) {
    Object val = condition.clause.getThirdTag().val;
    boolean isNodesetObjectList = condition.clause.nodeSetPresent &&  (val instanceof List) && ((List)val).get(0) instanceof Condition ;
    if(condition.clause.nodeSetPresent ){
      if(condition.computedType == ComputedType.EQUAL){
        if(!isNodesetObjectList) return " 'nodeset' must have an array value when 'replica': '#EQUAL` is used";
      } else {
        if(isNodesetObjectList){
          return "cannot use array value for nodeset if replica : '#EQUAL' is not used";
        }

      }

    }

    if (condition.computedType == ComputedType.EQUAL) {
      if (condition.getClause().tag != null &&
          (condition.getClause().tag.op == Operand.WILDCARD || condition.getClause().tag.op == Operand.IN)) {
        return null;
      } else {
        return "'replica': '#EQUAL` must be used with 'node':'#ANY'";
      }
    } else if (condition.computedType == ComputedType.ALL) {
      if(isNodesetObjectList) return "replica: '#ALL' cannot be used with a list of values in nodeset";
      if (condition.getClause().tag != null && (condition.getClause().getTag().op == Operand.IN ||
          condition.getClause().getTag().op == Operand.WILDCARD)) {
        return StrUtils.formatString("array value or wild card cannot be used for tag {0} with replica : '#ALL'",
            condition.getClause().tag.getName());
      }
    } else {
      return checkNonEqualOp(condition);
    }

    return null;
  }

  static String checkNonEqualOp(Condition condition) {
    if (condition.computedType == null &&
        condition.val instanceof RangeVal &&
        condition.op != Operand.RANGE_EQUAL) {
      return "non-integer values cannot have any other operators";
    }

    if(condition.computedType == ComputedType.PERCENT && condition.op != Operand.RANGE_EQUAL){
      return "percentage values cannot have any other operators";
    }
    return null;
  }


  @Override
  public Object computeValue(Condition cv, Clause.ComputedValueEvaluator evaluator) {
    if (cv.computedType == ComputedType.ALL) {
      int relevantReplicasCount = getRelevantReplicasCount(evaluator.session, cv, evaluator.collName, evaluator.shardName);
      return Double.valueOf(relevantReplicasCount);
    }
    if (cv.computedType == ComputedType.EQUAL) {
      int relevantReplicasCount = getRelevantReplicasCount(evaluator.session, cv, evaluator.collName, evaluator.shardName);
      double bucketsCount = getNumBuckets(evaluator.session, cv.getClause());
      if (relevantReplicasCount == 0 || bucketsCount == 0) return 0;
      return (double) relevantReplicasCount / bucketsCount;
    } else if (cv.computedType == ComputedType.PERCENT) {
      return ComputedType.PERCENT.compute(getRelevantReplicasCount(evaluator.session, cv, evaluator.collName, evaluator.shardName), cv);
    } else {
      throw new IllegalArgumentException("Unsupported type " + cv.computedType);

    }
  }

  private int getNumBuckets(Policy.Session session, Clause clause) {
    if (clause.getTag().getOperand() == Operand.IN) {
      return ((Collection) clause.getTag().val).size();
    } else if (clause.getTag().getOperand() == Operand.WILDCARD) {
      if (clause.getTag().varType == Type.NODE) return session.matrix.size();
      Set uniqueVals = new HashSet();
      for (Row matrix : session.matrix) {
        Object val = matrix.getVal(clause.getTag().name);
        if (val != null) uniqueVals.add(val);
      }
      return uniqueVals.size();
    } else {
      throw new IllegalArgumentException("Invalid operand for the tag in  " + clause);
    }

  }
}