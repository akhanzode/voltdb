/* This file is part of VoltDB.
 * Copyright (C) 2020 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

#include "storage/TopicTupleStream.h"
#include "catalog/catalogmap.h"
#include "catalog/cluster.h"
#include "catalog/database.h"
#include "catalog/topic.h"

#include "harness.h"

using namespace voltdb;

class TopicTupleStreamTest : public Test {
public:
    TopicTupleStreamTest() {
        m_topend.reset(new DummyTopend());
        m_pool.reset(new Pool());
        m_context.reset(new ExecutorContext(0, 0, nullptr, m_topend.get(), m_pool.get(), &m_engine, "", 0, NULL, NULL, 0));

        m_catalog.execute("add / clusters cluster\nadd /clusters#cluster databases database\n");
        m_database = m_catalog.clusters().get("cluster")->databases().get("database");
        m_schema = nullptr;
    }

    ~TopicTupleStreamTest() {
        TupleSchema::freeTupleSchema(m_schema);
    }

protected:
    const catalog::Topic* createTopic(const std::string& name, int32_t consumerKeySchemaId = 0,
            int32_t consumerValueSchemaId = 0) {
        std::ostringstream catalog;
        catalog << "add /clusters#cluster/databases#database tables " << name << "\n" <<
                "set /clusters#cluster/databases#database/tables#" << name << " topicName \"" << name << "\"\n" <<
                "add /clusters#cluster/databases#database topics " << name << "\n" <<
                "set /clusters#cluster/databases#database/topics#" << name << " streamName \"" << name << "\"\n" <<
                "set $PREV consumerKeySchemaId " << consumerKeySchemaId << "\n" <<
                "set $PREV consumerValueSchemaId " << consumerValueSchemaId << "\n";

        m_catalog.execute(catalog.str());
        return m_database->topics().get(name);
    }

    void addProperties(const catalog::Topic* topic, const TopicProperties& props) {
        std::ostringstream stream;
        for (auto& entry : props) {
            stream << "add /clusters#cluster/databases#database/topics#" << topic->name() << " properties " << entry.first << '\n'
                    << "set /clusters#cluster/databases#database/topics#" << topic->name() << "/properties#" << entry.first << " value \"" << entry.second << "\"\n";
        }
        m_catalog.execute(stream.str());
    }

    bool readAndValidateHeader(ExportSerializeInput& in, const void*& endPointer, int64_t firstOffset,
            int64_t firstTimestamp, int64_t lastTimestamp, int32_t recordCount) {
        ASSERT_EQ(firstOffset, in.readLong(), false);
        int32_t length = in.readInt();
        endPointer = static_cast<const int8_t*>(in.getRawPointer(0)) + length;

        ASSERT_EQ(-1, in.readInt(), false); // partition leader epoch
        ASSERT_EQ(2, in.readByte(), false); // magic number
        int32_t expectedCrc = in.readInt();
        int32_t calculatedCrc = vdbcrc::crc32cInit();
        calculatedCrc = vdbcrc::crc32c(calculatedCrc, in.getRawPointer(0),
                length - sizeof(expectedCrc) - sizeof(int32_t) - sizeof(int8_t));
        calculatedCrc = vdbcrc::crc32cFinish(calculatedCrc);
        ASSERT_EQ(expectedCrc, calculatedCrc, false);

        ASSERT_EQ(8, in.readShort(), false); // attributes
        ASSERT_EQ(recordCount - 1, in.readInt(), false); // offset delta
        ASSERT_EQ(firstTimestamp, in.readLong(), false);
        ASSERT_EQ(lastTimestamp, in.readLong(), false);

        ASSERT_EQ(-1, in.readLong(), false); // Producer ID
        ASSERT_EQ(-1, in.readShort(), false); // Producer epoch
        ASSERT_EQ(-1, in.readInt(), false); // sequence ID

        int32_t actualRecordCount = in.readInt();
        ASSERT_EQ(recordCount, actualRecordCount, false);

        return true;
    }

    bool readEntry(ExportSerializeInput& in, std::function<bool(ExportSerializeInput*)> validator) {
        int32_t length = in.readVarInt();
        if (length < 0) {
            return validator(nullptr);
        }
        ExportSerializeInput entry(in.getRawPointer(length), length);
        return validator(&entry);
    }

    bool readAndValidateRecord(ExportSerializeInput& in, int64_t timestampDelta, int64_t offsetDelta,
            std::function<bool(ExportSerializeInput*)> keyValidator,
            std::function<bool(ExportSerializeInput*)> valueValidator) {
        int32_t length = in.readVarInt();
        const void* expectedEnd = static_cast<const char*>(in.getRawPointer(0)) + length;
        ASSERT_EQ(0, in.readByte(), false); // attributes
        ASSERT_EQ(timestampDelta, in.readVarLong(), false);
        ASSERT_EQ(offsetDelta, in.readVarLong(), false);
        ASSERT_TRUE(readEntry(in, keyValidator), false);
        ASSERT_TRUE(readEntry(in, valueValidator), false);
        ASSERT_EQ(0, in.readVarInt(), false); // header count
        ASSERT_EQ(expectedEnd, in.getRawPointer(0), false);
        return true;
    }

    void setupTuples(const std::vector<ValueType>& types, const std::vector<int32_t>& sizes,
            const std::vector<bool>& nullables, int32_t count) {
        m_schema = TupleSchema::createTupleSchemaForTest(types, sizes, nullables);
        int size = m_tuples.size();
        for (int i = size; i < size + count; ++i) {
            m_tuples.emplace_back(m_schema);
            m_tuplesData.emplace_back(new char[m_tuples[i].tupleLength()]);
            m_tuples[i].moveAndInitialize(m_tuplesData[i].get());
        }
    }

    static const int32_t s_batchHeaderStart = ExportTupleStream::s_EXPORT_BUFFER_HEADER_SIZE
            + MAGIC_HEADER_SPACE_FOR_JAVA;

    VoltDBEngine m_engine;
    std::unique_ptr<DummyTopend> m_topend;
    std::unique_ptr<Pool> m_pool;
    std::unique_ptr<ExecutorContext> m_context;
    catalog::Catalog m_catalog;
    catalog::Database* m_database;

    TupleSchema *m_schema;
    std::vector<std::unique_ptr<char[]>> m_tuplesData;
    std::vector<TableTuple> m_tuples;
};

TEST_F(TopicTupleStreamTest, GetTopicForStream) {
    const catalog::Topic* topic = createTopic("topic");

    std::vector<ValueType> types {ValueType::tBIGINT, ValueType::tBIGINT};
    std::vector<int32_t> sizes {8, 8};
    std::vector<bool> nullables {false, false};
    setupTuples(types, sizes, nullables, 0);

    std::vector<std::string> columnNames {"key", "value"};
    std::unique_ptr<StreamedTable> stream(StreamedTable::createForTest(1024 * 1024, m_context.get(), m_schema, "topic", columnNames));

    // Default currently is topic is not returned
    ASSERT_EQ(nullptr, TopicTupleStream::getTopicForStream(*stream, *m_database));

    TopicProperties props;
    props[TopicTupleStream::PROP_STORE_ENCODED] = "TRUE";
    addProperties(topic, props);

    // Should now return the topic
    ASSERT_NE(nullptr, TopicTupleStream::getTopicForStream(*stream, *m_database));

    // if store is false no topic should be returned
    props[TopicTupleStream::PROP_STORE_ENCODED] = "false";
    addProperties(topic, props);
    ASSERT_EQ(nullptr, TopicTupleStream::getTopicForStream(*stream, *m_database));

    // No associated topic so nullptr should be returned
    m_catalog.execute("set /clusters#cluster/databases#database/tables#topic topicName \"\"\n");
    ASSERT_EQ(nullptr, TopicTupleStream::getTopicForStream(*stream, *m_database));
}

/**
 * Test that when no columns are selected null gets encoded for key and value
 */
TEST_F(TopicTupleStreamTest, NullEncoders) {
    const catalog::Topic* topic = createTopic("topic");
    TopicProperties props;
    props[TopicTupleStream::PROP_CONSUMER_VALUES] = "";
    addProperties(topic, props);

    std::vector<ValueType> types {ValueType::tBIGINT, ValueType::tBIGINT};
    std::vector<int32_t> sizes {8, 8};
    std::vector<bool> nullables {false, false};
    setupTuples(types, sizes, nullables, 1);

    std::vector<std::string> columnNames {"key", "value"};
    std::unique_ptr<StreamedTable> stream(StreamedTable::createForTest(1024 * 1024, m_context.get(), m_schema, "topic", columnNames));
    std::unique_ptr<TopicTupleStream> tts(TopicTupleStream::create(*stream, *topic, 1, 1, 1));

    m_tuples[0].setNValue(0, ValueFactory::getBigIntValue(5));
    m_tuples[0].setNValue(1, ValueFactory::getBigIntValue(10));

    int64_t timestamp = 789512 + VOLT_EPOCH_IN_MILLIS;
    UniqueId uniqueId = UniqueId::makeIdFromComponents(timestamp, 5, 1);
    tts->appendTuple(m_context->getEngine(), 1, 1, uniqueId.uid, m_tuples[0], 0, ExportTupleStream::STREAM_ROW_TYPE::INSERT);
    tts->commit(m_context->getEngine(), 1, uniqueId.uid);

    ASSERT_FALSE(m_topend->receivedExportBuffer);
    tts->periodicFlush(-1, 0);
    ASSERT_TRUE(m_topend->receivedExportBuffer);

    ASSERT_EQ(1, m_topend->partitionIds.front());
    m_topend->partitionIds.pop();
    m_topend->signatures.pop();
    boost::shared_array<char> buffer = m_topend->data.front();
    m_topend->data.pop_front();

    ExportSerializeInput esi(&buffer.get()[s_batchHeaderStart], 1024);

    // Validate the batch header
    const void* endPointer;
    ASSERT_TRUE(readAndValidateHeader(esi, endPointer, 1, timestamp, timestamp, 1));

    // Validate the record
    ASSERT_TRUE(readAndValidateRecord(esi, 0, 0,
            [](ExportSerializeInput *in) { return in == nullptr; },
            [](ExportSerializeInput *in) { return in == nullptr; }));

    ASSERT_EQ(endPointer, esi.getRawPointer(0));
}

TEST_F(TopicTupleStreamTest, SimpleTypeEncoders) {
    std::vector<ValueType> types { ValueType::tINTEGER, ValueType::tBIGINT, ValueType::tDOUBLE, ValueType::tVARCHAR,
            ValueType::tVARBINARY };
    std::vector<int32_t> sizes { 4, 8, 8, 1024, 1024 };
    std::vector<bool> nullables { false, false, false, false, false };
    setupTuples(types, sizes, nullables, 3);

    std::vector<std::string> columnNames {"integer", "bigint", "double", "varchar", "varbinary"};
    std::unique_ptr<StreamedTable> stream(StreamedTable::createForTest(1024 * 1024, m_context.get(), m_schema, "topic",
            columnNames));

    const catalog::Topic* topic = createTopic("topic");

    int64_t timestamp1 = 789512 + VOLT_EPOCH_IN_MILLIS;
    UniqueId uniqueId1 = UniqueId::makeIdFromComponents(timestamp1, 5, 1);
    int64_t timestamp2 = timestamp1 + 500;
    UniqueId uniqueId2 = UniqueId::makeIdFromComponents(timestamp2, 5, 1);

    // setup tuple 0
    m_tuples[0].setNValue(0, ValueFactory::getIntegerValue(1));
    m_tuples[0].setNValue(1, ValueFactory::getBigIntValue(2));
    m_tuples[0].setNValue(2, ValueFactory::getDoubleValue(3));
    m_tuples[0].setNValue(3, ValueFactory::getStringValue("4", m_pool.get()));
    m_tuples[0].setNValue(4, ValueFactory::getBinaryValue("05", m_pool.get()));

    // setup tuple 1
    m_tuples[1].setNValue(0, ValueFactory::getIntegerValue(6));
    m_tuples[1].setNValue(1, ValueFactory::getBigIntValue(7));
    m_tuples[1].setNValue(2, ValueFactory::getDoubleValue(8));
    m_tuples[1].setNValue(3, ValueFactory::getStringValue("9", m_pool.get()));
    m_tuples[1].setNValue(4, ValueFactory::getBinaryValue("0A", m_pool.get()));

    // Test with int and bigint
    TopicProperties props;
    props[TopicTupleStream::PROP_STORE_ENCODED] = "true";
    props[TopicTupleStream::PROP_CONSUMER_KEYS] = "integer";
    props[TopicTupleStream::PROP_CONSUMER_VALUES] = "bigint";
    addProperties(topic, props);

    std::unique_ptr<TopicTupleStream> tts(TopicTupleStream::create(*stream, *topic, 1, 1, 1));
    {
        tts->appendTuple(&m_engine, 1, 1, uniqueId1, m_tuples[0], 1, ExportTupleStream::STREAM_ROW_TYPE::INSERT);
        tts->commit(&m_engine, 1, uniqueId1);
        tts->appendTuple(&m_engine, 2, 2, uniqueId2, m_tuples[1], 1, ExportTupleStream::STREAM_ROW_TYPE::INSERT);
        tts->commit(&m_engine, 2, uniqueId2);

        ASSERT_FALSE(m_topend->receivedExportBuffer);
        tts->periodicFlush(-1, 0);
        ASSERT_TRUE(m_topend->receivedExportBuffer);

        ASSERT_EQ(1, m_topend->partitionIds.front());
        m_topend->partitionIds.pop();
        m_topend->signatures.pop();
        boost::shared_array<char> buffer = m_topend->data.front();
        m_topend->data.pop_front();

        ExportSerializeInput esi(&buffer.get()[s_batchHeaderStart], 1024);

        // Validate the batch header and records
        const void* endPointer;
        ASSERT_TRUE(readAndValidateHeader(esi, endPointer, 1, timestamp1, timestamp2, 2));
        ASSERT_TRUE(readAndValidateRecord(esi, 0, 0,
                [](ExportSerializeInput *in) { return in != nullptr && in->remaining() == 4 && 1 == in->readInt(); },
                [](ExportSerializeInput *in) { return in != nullptr && in->remaining() == 8 && 2 == in->readLong(); }));
        ASSERT_TRUE(readAndValidateRecord(esi, timestamp2 - timestamp1, 1,
                [](ExportSerializeInput *in) { return in != nullptr && in->remaining() == 4 && 6 == in->readInt(); },
                [](ExportSerializeInput *in) { return in != nullptr && in->remaining() == 8 && 7 == in->readLong(); }));

        ASSERT_EQ(endPointer, esi.getRawPointer(0));
    }

    // Now try double and varchar
    {
        props[TopicTupleStream::PROP_CONSUMER_KEYS] = "double";
        props[TopicTupleStream::PROP_CONSUMER_VALUES] = "varchar";
        addProperties(topic, props);

        tts->update(*stream, *m_database);

        tts->appendTuple(&m_engine, 3, 1, uniqueId1, m_tuples[0], 1, ExportTupleStream::STREAM_ROW_TYPE::INSERT);
        tts->commit(&m_engine, 3, uniqueId1);
        tts->appendTuple(&m_engine, 4, 2, uniqueId2, m_tuples[1], 1, ExportTupleStream::STREAM_ROW_TYPE::INSERT);
        tts->commit(&m_engine, 4, uniqueId2);

        m_topend->receivedExportBuffer = false;
        tts->periodicFlush(-1, 0);
        ASSERT_TRUE(m_topend->receivedExportBuffer);

        ASSERT_EQ(1, m_topend->partitionIds.front());
        m_topend->partitionIds.pop();
        m_topend->signatures.pop();
        boost::shared_array<char> buffer = m_topend->data.front();
        m_topend->data.pop_front();

        ExportSerializeInput esi(&buffer.get()[s_batchHeaderStart], 1024);

        // Validate the batch header and records
        const void* endPointer;
        ASSERT_TRUE(readAndValidateHeader(esi, endPointer, 3, timestamp1, timestamp2, 2));
        ASSERT_TRUE(readAndValidateRecord(esi, 0, 0,
                [](ExportSerializeInput *in) { return in != nullptr && 3 == in->readDouble(); },
                [](ExportSerializeInput *in) { return in != nullptr &&
                        "4" == std::string(static_cast<const char*>(in->getRawPointer(0)), in->remaining()); }));
        ASSERT_TRUE(readAndValidateRecord(esi, timestamp2 - timestamp1, 1,
                [](ExportSerializeInput *in) { return in != nullptr && 8 == in->readDouble(); },
                [](ExportSerializeInput *in) { return in != nullptr &&
                        "9" == std::string(static_cast<const char*>(in->getRawPointer(0)), in->remaining()); }));

        ASSERT_EQ(endPointer, esi.getRawPointer(0));
    }

    // Now try varbinary and null
    {
        props[TopicTupleStream::PROP_CONSUMER_KEYS] = "varbinary";
        props[TopicTupleStream::PROP_CONSUMER_VALUES] = "";
        addProperties(topic, props);

        tts->update(*stream, *m_database);

        tts->appendTuple(&m_engine, 5, 1, uniqueId1, m_tuples[0], 1, ExportTupleStream::STREAM_ROW_TYPE::INSERT);
        tts->commit(&m_engine, 5, uniqueId1);
        tts->appendTuple(&m_engine, 5, 2, uniqueId2, m_tuples[1], 1, ExportTupleStream::STREAM_ROW_TYPE::INSERT);
        tts->commit(&m_engine, 5, uniqueId2);

        m_topend->receivedExportBuffer = false;
        tts->periodicFlush(-1, 0);
        ASSERT_TRUE(m_topend->receivedExportBuffer);

        ASSERT_EQ(1, m_topend->partitionIds.front());
        m_topend->partitionIds.pop();
        m_topend->signatures.pop();
        boost::shared_array<char> buffer = m_topend->data.front();
        m_topend->data.pop_front();

        ExportSerializeInput esi(&buffer.get()[s_batchHeaderStart], 1024);

        // Validate the batch header and records
        const void* endPointer;
        ASSERT_TRUE(readAndValidateHeader(esi, endPointer, 5, timestamp1, timestamp2, 2));
        ASSERT_TRUE(readAndValidateRecord(esi, 0, 0,
                [](ExportSerializeInput *in) { return in != nullptr && in->remaining() == 1 && 5 == in->readChar(); },
                [](ExportSerializeInput *in) { return in == nullptr; }));
        ASSERT_TRUE(readAndValidateRecord(esi, timestamp2 - timestamp1, 1,
                [](ExportSerializeInput *in) { return in != nullptr && in->remaining() == 1 && 10 == in->readChar(); },
                [](ExportSerializeInput *in) { return in == nullptr; }));

        ASSERT_EQ(endPointer, esi.getRawPointer(0));
    }
}

// Test multiple column encodings
TEST_F(TopicTupleStreamTest, MultiColumnEncoder) {
    std::vector<ValueType> types { ValueType::tINTEGER, ValueType::tBIGINT, ValueType::tDOUBLE, ValueType::tVARCHAR,
            ValueType::tVARBINARY };
    std::vector<int32_t> sizes { 4, 8, 8, 1024, 1024 };
    std::vector<bool> nullables { false, false, false, false, false };
    setupTuples(types, sizes, nullables, 3);

    std::vector<std::string> columnNames {"integer", "bigint", "double", "varchar", "varbinary"};
    std::unique_ptr<StreamedTable> stream(StreamedTable::createForTest(1024 * 1024, m_context.get(), m_schema, "topic",
            columnNames));

    const int32_t keySchemaId = 15, valueSchemaId = 25;
    const catalog::Topic* topic = createTopic("topic", keySchemaId, valueSchemaId);

    int64_t timestamp1 = 789512 + VOLT_EPOCH_IN_MILLIS;
    UniqueId uniqueId1 = UniqueId::makeIdFromComponents(timestamp1, 5, 1);
    int64_t timestamp2 = timestamp1 + 500;
    UniqueId uniqueId2 = UniqueId::makeIdFromComponents(timestamp2, 5, 1);

    // setup tuple 0
    m_tuples[0].setNValue(0, ValueFactory::getIntegerValue(1));
    m_tuples[0].setNValue(1, ValueFactory::getBigIntValue(2));
    m_tuples[0].setNValue(2, ValueFactory::getDoubleValue(3));
    m_tuples[0].setNValue(3, ValueFactory::getStringValue("4", m_pool.get()));
    m_tuples[0].setNValue(4, ValueFactory::getBinaryValue("05", m_pool.get()));

    // setup tuple 1
    m_tuples[1].setNValue(0, ValueFactory::getIntegerValue(6));
    m_tuples[1].setNValue(1, ValueFactory::getBigIntValue(7));
    m_tuples[1].setNValue(2, ValueFactory::getDoubleValue(8));
    m_tuples[1].setNValue(3, ValueFactory::getStringValue("9", m_pool.get()));
    m_tuples[1].setNValue(4, ValueFactory::getBinaryValue("0A", m_pool.get()));

    // Test with default columns with avro value
    TopicProperties props;
    props[TopicTupleStream::PROP_STORE_ENCODED] = "true";
    props[TopicTupleStream::PROP_CONSUMER_FORMAT_VALUES] = "AVRO";
    addProperties(topic, props);

    std::unique_ptr<TopicTupleStream> tts(TopicTupleStream::create(*stream, *topic, 1, 1, 1));
    {
        tts->appendTuple(&m_engine, 1, 1, uniqueId1, m_tuples[0], 1, ExportTupleStream::STREAM_ROW_TYPE::INSERT);
        tts->commit(&m_engine, 1, uniqueId1);
        tts->appendTuple(&m_engine, 2, 2, uniqueId2, m_tuples[1], 1, ExportTupleStream::STREAM_ROW_TYPE::INSERT);
        tts->commit(&m_engine, 2, uniqueId2);

        ASSERT_FALSE(m_topend->receivedExportBuffer);
        tts->periodicFlush(-1, 0);
        ASSERT_TRUE(m_topend->receivedExportBuffer);

        ASSERT_EQ(1, m_topend->partitionIds.front());
        m_topend->partitionIds.pop();
        m_topend->signatures.pop();
        boost::shared_array<char> buffer = m_topend->data.front();
        m_topend->data.pop_front();

        ExportSerializeInput esi(&buffer.get()[s_batchHeaderStart], 1024);

        // Validate the batch header
        const void* endPointer;
        ASSERT_TRUE(readAndValidateHeader(esi, endPointer, 1, timestamp1, timestamp2, 2));

        // validate entries see AvroEncoderTest for avro format layout
        ASSERT_TRUE(readAndValidateRecord(esi, 0, 0,
                [](ExportSerializeInput *in) { return in == nullptr; },
                [this](ExportSerializeInput *in) {
                        ASSERT_TRUE(in, false);
                        ASSERT_EQ(0, in->readByte(), false);
                        ASSERT_EQ(valueSchemaId, in->readInt(), false);
                        ASSERT_EQ(1, in->readVarInt(), false);
                        ASSERT_EQ(2, in->readVarLong(), false);
                        ASSERT_EQ(3, in->readDouble(), false);
                        ASSERT_EQ(1, in->readVarInt(), false);
                        ASSERT_EQ("4", std::string(static_cast<const char*>(in->getRawPointer(1)), 1), false);
                        ASSERT_EQ(1, in->readVarInt(), false);
                        ASSERT_EQ(5, in->readByte(), false);
                        ASSERT_EQ(0, in->remaining(), false);
                        return true; }));
        ASSERT_TRUE(readAndValidateRecord(esi, timestamp2 - timestamp1, 1,
                [](ExportSerializeInput *in) { return in == nullptr; },
                [this](ExportSerializeInput *in) {
                        ASSERT_TRUE(in, false);
                        ASSERT_EQ(0, in->readByte(), false);
                        ASSERT_EQ(valueSchemaId, in->readInt(), false);
                        ASSERT_EQ(6, in->readVarInt(), false);
                        ASSERT_EQ(7, in->readVarLong(), false);
                        ASSERT_EQ(8, in->readDouble(), false);
                        ASSERT_EQ(1, in->readVarInt(), false);
                        ASSERT_EQ("9", std::string(static_cast<const char*>(in->getRawPointer(1)), 1), false);
                        ASSERT_EQ(1, in->readVarInt(), false);
                        ASSERT_EQ(10, in->readByte(), false);
                        ASSERT_EQ(0, in->remaining(), false);
                        return true; }));

        ASSERT_EQ(endPointer, esi.getRawPointer(0));
    }

    // Now try it with a key and some value columns
    props[TopicTupleStream::PROP_CONSUMER_KEYS] = "integer, double";
    props[TopicTupleStream::PROP_CONSUMER_VALUES] = "bigint, varchar, varbinary";
    props[TopicTupleStream::PROP_CONSUMER_FORMAT] = "AVRO";
    addProperties(topic, props);

    tts->update(*stream, *m_database);
    {
        tts->appendTuple(&m_engine, 3, 1, uniqueId1, m_tuples[0], 1, ExportTupleStream::STREAM_ROW_TYPE::INSERT);
        tts->commit(&m_engine, 3, uniqueId1);
        tts->appendTuple(&m_engine, 4, 2, uniqueId2, m_tuples[1], 1, ExportTupleStream::STREAM_ROW_TYPE::INSERT);
        tts->commit(&m_engine, 4, uniqueId2);

        m_topend->receivedExportBuffer = false;
        tts->periodicFlush(-1, 0);
        ASSERT_TRUE(m_topend->receivedExportBuffer);

        ASSERT_EQ(1, m_topend->partitionIds.front());
        m_topend->partitionIds.pop();
        m_topend->signatures.pop();
        boost::shared_array<char> buffer = m_topend->data.front();
        m_topend->data.pop_front();

        ExportSerializeInput esi(&buffer.get()[s_batchHeaderStart], 1024);

        // Validate the batch header
        const void* endPointer;
        ASSERT_TRUE(readAndValidateHeader(esi, endPointer, 3, timestamp1, timestamp2, 2));

        // validate entries see AvroEncoderTest for avro format layout
        ASSERT_TRUE(readAndValidateRecord(esi, 0, 0,
                [this](ExportSerializeInput *in) {
                        ASSERT_TRUE(in, false);
                        ASSERT_EQ(0, in->readByte(), false);
                        ASSERT_EQ(keySchemaId, in->readInt(), false);
                        ASSERT_EQ(1, in->readVarInt(), false);
                        ASSERT_EQ(3, in->readDouble(), false);
                        ASSERT_EQ(0, in->remaining(), false);
                        return true; },
                [this](ExportSerializeInput *in) {
                        ASSERT_TRUE(in, false);
                        ASSERT_EQ(0, in->readByte(), false);
                        ASSERT_EQ(valueSchemaId, in->readInt(), false);
                        ASSERT_EQ(2, in->readVarLong(), false);
                        ASSERT_EQ(1, in->readVarInt(), false);
                        ASSERT_EQ("4", std::string(static_cast<const char*>(in->getRawPointer(1)), 1), false);
                        ASSERT_EQ(1, in->readVarInt(), false);
                        ASSERT_EQ(5, in->readByte(), false);
                        ASSERT_EQ(0, in->remaining(), false);
                        return true; }));
        ASSERT_TRUE(readAndValidateRecord(esi, timestamp2 - timestamp1, 1,
                [this](ExportSerializeInput *in) {
                        ASSERT_TRUE(in, false);
                        ASSERT_EQ(0, in->readByte(), false);
                        ASSERT_EQ(keySchemaId, in->readInt(), false);
                        ASSERT_EQ(6, in->readVarInt(), false);
                        ASSERT_EQ(8, in->readDouble(), false);
                        ASSERT_EQ(0, in->remaining(), false);
                        return true; },
                [this](ExportSerializeInput *in) {
                        ASSERT_TRUE(in, false);
                        ASSERT_EQ(0, in->readByte(), false);
                        ASSERT_EQ(valueSchemaId, in->readInt(), false);
                        ASSERT_EQ(7, in->readVarLong(), false);
                        ASSERT_EQ(1, in->readVarInt(), false);
                        ASSERT_EQ("9", std::string(static_cast<const char*>(in->getRawPointer(1)), 1), false);
                        ASSERT_EQ(1, in->readVarInt(), false);
                        ASSERT_EQ(10, in->readByte(), false);
                        ASSERT_EQ(0, in->remaining(), false);
                        return true; }));

        ASSERT_EQ(endPointer, esi.getRawPointer(0));
    }
}

int main() {
    return TestSuite::globalInstance()->runAll();
}
