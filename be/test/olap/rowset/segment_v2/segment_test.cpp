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

#include "olap/rowset/segment_v2/segment.h"
#include "olap/rowset/segment_v2/segment_writer.h"
#include "olap/rowset/segment_v2/segment_iterator.h"

#include <gtest/gtest.h>
#include <iostream>

#include "common/logging.h"
#include "olap/olap_common.h"
#include "olap/row_cursor.h"
#include "olap/tablet_schema.h"
#include "olap/row_block.h"
#include "olap/row_block2.h"
#include "olap/types.h"
#include "olap/tablet_schema_helper.h"
#include "util/file_utils.h"

namespace doris {
namespace segment_v2 {

class SegmentReaderWriterTest : public testing::Test {
public:
    SegmentReaderWriterTest() { }
    virtual ~SegmentReaderWriterTest() {
    }
};

TEST_F(SegmentReaderWriterTest, normal) {

    size_t num_rows_per_block = 10;

    std::shared_ptr<TabletSchema> tablet_schema(new TabletSchema());
    tablet_schema->_num_columns = 4;
    tablet_schema->_num_key_columns = 3;
    tablet_schema->_num_short_key_columns = 2;
    tablet_schema->_num_rows_per_row_block = num_rows_per_block;
    tablet_schema->_cols.push_back(create_int_key(1));
    tablet_schema->_cols.push_back(create_int_key(2));
    tablet_schema->_cols.push_back(create_int_key(3));
    tablet_schema->_cols.push_back(create_int_value(4));

    // segment write
    std::string dname = "./ut_dir/segment_test";
    FileUtils::create_dir(dname);

    SegmentWriterOptions opts;
    opts.num_rows_per_block = num_rows_per_block;

    std::string fname = dname + "/int_case";
    SegmentWriter writer(fname, 0, tablet_schema.get(), opts);
    auto st = writer.init(10);
    ASSERT_TRUE(st.ok());

    RowCursor row;
    auto olap_st = row.init(*tablet_schema);
    ASSERT_EQ(OLAP_SUCCESS, olap_st);

    // 0, 1, 2, 3
    // 10, 11, 12, 13
    // 20, 21, 22, 23
    for (int i = 0; i < 4096; ++i) {
        for (int j = 0; j < 4; ++j) {
            auto cell = row.cell(j);
            cell.set_not_null();
            *(int*)cell.mutable_cell_ptr() = i * 10 + j;
        }
        writer.append_row(row);
    }

    uint64_t file_size = 0;
    st = writer.finalize(&file_size);
    ASSERT_TRUE(st.ok());
    // reader
    {
        std::shared_ptr<Segment> segment(new Segment(fname, 0, tablet_schema.get()));
        st = segment->open();
        LOG(INFO) << "segment open, msg=" << st.to_string();
        ASSERT_TRUE(st.ok());
        ASSERT_EQ(4096, segment->num_rows());
        Schema schema(*tablet_schema);
        // scan all rows
        {
            StorageReadOptions read_opts;
            std::unique_ptr<SegmentIterator> iter = segment->new_iterator(schema, read_opts);

            RowBlockV2 block(schema, 1024);

            int left = 4096;

            int rowid = 0;
            while (left > 0)  {
                int rows_read = left > 1024 ? 1024 : left;
                block.clear();
                st = iter->next_batch(&block);
                ASSERT_TRUE(st.ok());
                ASSERT_EQ(rows_read, block.num_rows());
                left -= rows_read;

                for (int j = 0; j < block.schema()->column_ids().size(); ++j) {
                    auto cid = block.schema()->column_ids()[j];
                    auto column_block = block.column_block(j);
                    for (int i = 0; i < rows_read; ++i) {
                        int rid = rowid + i;
                        ASSERT_FALSE(BitmapTest(column_block.null_bitmap(), i));
                        ASSERT_EQ(rid * 10 + cid, *(int*)column_block.cell_ptr(i));
                    }
                }
                rowid += rows_read;
            }
        }
        // test seek, key
        {
            // lower bound
            std::unique_ptr<RowCursor> lower_bound(new RowCursor());
            lower_bound->init(*tablet_schema, 2);
            {
                auto cell = lower_bound->cell(0);
                cell.set_not_null();
                *(int*)cell.mutable_cell_ptr() = 100;
            }
            {
                auto cell = lower_bound->cell(1);
                cell.set_not_null();
                *(int*)cell.mutable_cell_ptr() = 100;
            }

            // upper bound
            std::unique_ptr<RowCursor> upper_bound(new RowCursor());
            upper_bound->init(*tablet_schema, 1);
            {
                auto cell = upper_bound->cell(0);
                cell.set_not_null();
                *(int*)cell.mutable_cell_ptr() = 200;
            }

            StorageReadOptions read_opts;
            read_opts.key_ranges.emplace_back(lower_bound.get(), false, upper_bound.get(), true);
            std::unique_ptr<SegmentIterator> iter = segment->new_iterator(schema, read_opts);

            RowBlockV2 block(schema, 100);
            st = iter->next_batch(&block);
            ASSERT_TRUE(st.ok());
            ASSERT_EQ(11, block.num_rows());
            auto column_block = block.column_block(0);
            for (int i = 0; i < 11; ++i) {
                ASSERT_EQ(100 + i * 10, *(int*)column_block.cell_ptr(i));
            }
        }
        // test seek, key
        {
            // lower bound
            std::unique_ptr<RowCursor> lower_bound(new RowCursor());
            lower_bound->init(*tablet_schema, 1);
            {
                auto cell = lower_bound->cell(0);
                cell.set_not_null();
                *(int*)cell.mutable_cell_ptr() = 40970;
            }

            StorageReadOptions read_opts;
            read_opts.key_ranges.emplace_back(lower_bound.get(), false, nullptr, false);
            std::unique_ptr<SegmentIterator> iter = segment->new_iterator(schema, read_opts);

            RowBlockV2 block(schema, 100);
            st = iter->next_batch(&block);
            ASSERT_TRUE(st.is_end_of_file());
            ASSERT_EQ(0, block.num_rows());
        }
        // test seek, key (-2, -1)
        {
            // lower bound
            std::unique_ptr<RowCursor> lower_bound(new RowCursor());
            lower_bound->init(*tablet_schema, 1);
            {
                auto cell = lower_bound->cell(0);
                cell.set_not_null();
                *(int*)cell.mutable_cell_ptr() = -2;
            }

            std::unique_ptr<RowCursor> upper_bound(new RowCursor());
            upper_bound->init(*tablet_schema, 1);
            {
                auto cell = upper_bound->cell(0);
                cell.set_not_null();
                *(int*)cell.mutable_cell_ptr() = -1;
            }

            StorageReadOptions read_opts;
            read_opts.key_ranges.emplace_back(lower_bound.get(), false, upper_bound.get(), false);
            std::unique_ptr<SegmentIterator> iter = segment->new_iterator(schema, read_opts);

            RowBlockV2 block(schema, 100);
            st = iter->next_batch(&block);
            ASSERT_TRUE(st.is_end_of_file());
            ASSERT_EQ(0, block.num_rows());
        }
    }
    FileUtils::remove_all(dname);
}

TEST_F(SegmentReaderWriterTest, TestZoneMap) {
    size_t num_rows_per_block = 10;

    std::shared_ptr<TabletSchema> tablet_schema(new TabletSchema());
    tablet_schema->_num_columns = 4;
    tablet_schema->_num_key_columns = 3;
    tablet_schema->_num_short_key_columns = 2;
    tablet_schema->_num_rows_per_row_block = num_rows_per_block;
    tablet_schema->_cols.push_back(create_int_key(1));
    tablet_schema->_cols.push_back(create_int_key(2));
    tablet_schema->_cols.push_back(create_int_key(3));
    tablet_schema->_cols.push_back(create_int_value(4));

    // segment write
    std::string dname = "./ut_dir/segment_test";
    FileUtils::create_dir(dname);

    SegmentWriterOptions opts;
    opts.num_rows_per_block = num_rows_per_block;

    std::string fname = dname + "/int_case2";
    SegmentWriter writer(fname, 0, tablet_schema.get(), opts);
    auto st = writer.init(10);
    ASSERT_TRUE(st.ok());

    RowCursor row;
    auto olap_st = row.init(*tablet_schema);
    ASSERT_EQ(OLAP_SUCCESS, olap_st);

    // 0, 1, 2, 3
    // 10, 11, 12, 13
    // 20, 21, 22, 23
    //
    // 64k int will generate 4 pages
    for (int i = 0; i < 64 * 1024; ++i) {
        for (int j = 0; j < 4; ++j) {
            auto cell = row.cell(j);
            cell.set_not_null();
            *(int*)cell.mutable_cell_ptr() = i * 10 + j;
        }
        writer.append_row(row);
    }

    uint64_t file_size = 0;
    st = writer.finalize(&file_size);
    ASSERT_TRUE(st.ok());

    // reader with condition
    {
        std::shared_ptr<Segment> segment(new Segment(fname, 0, tablet_schema.get()));
        st = segment->open();
        ASSERT_TRUE(st.ok());
        ASSERT_EQ(64 * 1024, segment->num_rows());
        Schema schema(*tablet_schema);
        // scan all rows
        {
            TCondition condition;
            condition.__set_column_name("2");
            condition.__set_condition_op("<");
            std::vector<std::string> vals = {"100"};
            condition.__set_condition_values(vals);
            std::shared_ptr<Conditions> conditions(new Conditions());
            conditions->set_tablet_schema(tablet_schema.get());
            conditions->append_condition(condition);

            StorageReadOptions read_opts;
            read_opts.conditions = conditions.get();

            std::unique_ptr<SegmentIterator> iter = segment->new_iterator(schema, read_opts);

            RowBlockV2 block(schema, 1024);

            // only first page will be read because of zone map
            int left = 16 * 1024;

            int rowid = 0;
            while (left > 0)  {
                int rows_read = left > 1024 ? 1024 : left;
                block.clear();
                st = iter->next_batch(&block);
                ASSERT_TRUE(st.ok());
                ASSERT_EQ(rows_read, block.num_rows());
                left -= rows_read;

                for (int j = 0; j < block.schema()->column_ids().size(); ++j) {
                    auto cid = block.schema()->column_ids()[j];
                    auto column_block = block.column_block(j);
                    for (int i = 0; i < rows_read; ++i) {
                        int rid = rowid + i;
                        ASSERT_FALSE(BitmapTest(column_block.null_bitmap(), i));
                        ASSERT_EQ(rid * 10 + cid, *(int*)column_block.cell_ptr(i)) << "rid:" << rid << ", i:" << i;
                    }
                }
                rowid += rows_read;
            }
            ASSERT_EQ(16 * 1024, rowid);
            st = iter->next_batch(&block);
            ASSERT_TRUE(st.is_end_of_file());
            ASSERT_EQ(0, block.num_rows());
        }
    }
    FileUtils::remove_all(dname);
}

}
}

int main(int argc, char** argv) {
    ::testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
}
