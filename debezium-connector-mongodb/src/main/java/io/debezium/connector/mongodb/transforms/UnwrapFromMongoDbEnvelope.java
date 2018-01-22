/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.mongodb.transforms;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.transforms.ExtractField;
import org.apache.kafka.connect.transforms.Transformation;
import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Debezium Mongo Connector generates the CDC records in String format. Sink connectors usually are not able to parse
 * the string and insert the document as it is represented in the Source. so a user use this SMT to parse the String
 * and insert the MongoDB document in the JSON format.
 *
 * @param <R> the subtype of {@link ConnectRecord} on which this transformation will operate
 * @author Sairam Polavarapu
 */
public class UnwrapFromMongoDbEnvelope<R extends ConnectRecord<R>> implements Transformation<R> {

    private final ExtractField<R> afterExtractor = new ExtractField.Value<R>();
    private final ExtractField<R> patchExtractor = new ExtractField.Value<R>();
    private final ExtractField<R> keyExtractor = new ExtractField.Key<R>();

    @Override
    public R apply(R r) {
        SchemaBuilder schemabuilder = SchemaBuilder.struct();
        SchemaBuilder schemabuilder1 = SchemaBuilder.struct();
        BsonDocument value = null;
        BsonDocument Key = null;

        final R afterRecord = afterExtractor.apply(r);
        final R key = keyExtractor.apply(r);
        Key = BsonDocument.parse("{ \"id\" : " + key.key().toString() + "}");

        if (afterRecord.value() == null) {
            final R patchRecord = patchExtractor.apply(r);
            value = BsonDocument.parse(patchRecord.value().toString());
            value = value.getDocument("$set");

            if (!value.containsKey("id")) {
                value.append("id", Key.get("id"));
            }
            } else {
                value = BsonDocument.parse(afterRecord.value().toString());
            }

        Set<Entry<String, BsonValue>> valuePairs = value.entrySet();
        Set<Entry<String, BsonValue>> keyPairs = Key.entrySet();

        for (Entry<String, BsonValue> valuePairsforSchema : valuePairs) {
            if(valuePairsforSchema.getKey().toString().equalsIgnoreCase("$set")) {
                BsonDocument val1 = BsonDocument.parse(valuePairsforSchema.getValue().toString());
                Set<Entry<String, BsonValue>> keyValuesforSetSchema = val1.entrySet();
                for (Entry<String, BsonValue> keyValuesforSetSchemaEntry : keyValuesforSetSchema) {
                    MongoDataConverter.addFieldSchema(keyValuesforSetSchemaEntry, schemabuilder);
                }
            } else {
                MongoDataConverter.addFieldSchema(valuePairsforSchema, schemabuilder);
            }
        }

        for (Entry<String, BsonValue> keyPairsforSchema : keyPairs) {
            MongoDataConverter.addFieldSchema(keyPairsforSchema, schemabuilder1);
        }

        Schema finalValueSchema = schemabuilder.build();
        Struct finalValueStruct = new Struct(finalValueSchema);
        Schema finalKeySchema = schemabuilder1.build();
        Struct finalKeyStruct = new Struct(finalKeySchema);

        for (Entry<String, BsonValue> valuePairsforStruct : valuePairs) {
            if(valuePairsforStruct.getKey().toString().equalsIgnoreCase("$set")) {
                BsonDocument val1 = BsonDocument.parse(valuePairsforStruct.getValue().toString());
                Set<Entry<String, BsonValue>> keyvalueforSetStruct = val1.entrySet();
                for (Entry<String, BsonValue> keyvalueforSetStructEntry : keyvalueforSetStruct) {
                    MongoDataConverter.convertRecord(keyvalueforSetStructEntry, finalValueSchema, finalValueStruct);
                }
            } else {
                MongoDataConverter.convertRecord(valuePairsforStruct, finalValueSchema, finalValueStruct);
            }
        }

        for (Entry<String, BsonValue> keyPairsforStruct : keyPairs) {
            MongoDataConverter.convertRecord(keyPairsforStruct, finalKeySchema, finalKeyStruct);
        }

        return r.newRecord(r.topic(), r.kafkaPartition(), finalKeySchema, finalKeyStruct, finalValueSchema, finalValueStruct,
                r.timestamp());
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef();
    }

    @Override
    public void close() {
    }

    @Override
    public void configure(final Map<String, ?> map) {
        final Map<String, String> afterExtractorConfig = new HashMap<>();
        afterExtractorConfig.put("field", "after");
        final Map<String, String> patchExtractorConfig = new HashMap<>();
        patchExtractorConfig.put("field", "patch");
        final Map<String, String> keyExtractorConfig = new HashMap<>();
        keyExtractorConfig.put("field", "id");
        afterExtractor.configure(afterExtractorConfig);
        patchExtractor.configure(patchExtractorConfig);
        keyExtractor.configure(keyExtractorConfig);
    }
}