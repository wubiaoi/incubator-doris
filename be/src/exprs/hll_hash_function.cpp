// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "exprs/aggregate_functions.h"
#include "exprs/hll_hash_function.h"

namespace doris {

using doris_udf::BigIntVal;
using doris_udf::StringVal;

void HllHashFunctions::init() {
}

StringVal HllHashFunctions::hll_hash(FunctionContext* ctx, const StringVal& input) {
    const int HLL_SINGLE_VALUE_SIZE = 10;
    const int HLL_EMPTY_SIZE = 1;
    std::string buf;
    std::unique_ptr<HyperLogLog> hll {new HyperLogLog()};
    if (!input.is_null) {
        uint64_t hash_value = HashUtil::murmur_hash64A(input.ptr, input.len, HashUtil::MURMUR_SEED);
        hll.reset(new HyperLogLog(hash_value));
        buf.resize(HLL_SINGLE_VALUE_SIZE);
    } else {
        buf.resize(HLL_EMPTY_SIZE);
    }
    hll->serialize((char*)buf.c_str());
    return AnyValUtil::from_string_temp(ctx, buf);
}

BigIntVal HllHashFunctions::hll_cardinality(FunctionContext* ctx, const HllVal& input) {
    if (input.is_null) {
        return BigIntVal::null();
    }
    HllVal dst;
    AggregateFunctions::hll_union_agg_init(ctx, &dst);
    AggregateFunctions::hll_union_agg_update(ctx, input, &dst);
    return AggregateFunctions::hll_union_agg_finalize(ctx, dst);
}

}
