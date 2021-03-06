/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.integration.search.sort;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.customScoreQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertFirstHit;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSecondHit;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.hasId;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.test.integration.AbstractSharedClusterTest;
import org.hamcrest.Matchers;
import org.testng.annotations.Test;


/**
 *
 */
public class SimpleSortTests extends AbstractSharedClusterTest {
    
    @Override
    public Settings getSettings() {
        return randomSettingsBuilder()
                .put("index.number_of_shards", 3)
                .put("index.number_of_replicas", 0)
                .build();
    }
    
    @Test
    public void testTrackScores() throws Exception {
        createIndex("test");
        ensureGreen();
        index("test", "type1", jsonBuilder().startObject()
                .field("id", "1")
                .field("svalue", "aaa")
                .field("ivalue", 100)
                .field("dvalue", 0.1)
                .endObject());
        index("test", "type1", jsonBuilder().startObject()
                .field("id", "2")
                .field("svalue", "bbb")
                .field("ivalue", 200)
                .field("dvalue", 0.2)
                .endObject());
        refresh();

        SearchResponse searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addSort("svalue", SortOrder.ASC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getMaxScore(), equalTo(Float.NaN));
        for (SearchHit hit : searchResponse.getHits()) {
            assertThat(hit.getScore(), equalTo(Float.NaN));
        }

        // now check with score tracking
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addSort("svalue", SortOrder.ASC)
                .setTrackScores(true)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getMaxScore(), not(equalTo(Float.NaN)));
        for (SearchHit hit : searchResponse.getHits()) {
            assertThat(hit.getScore(), not(equalTo(Float.NaN)));
        }
    }
    

    @Test
    public void test3078() {
        createIndex("test");
        ensureGreen();

        for (int i = 1; i < 101; i++) {
            client().prepareIndex("test", "type", Integer.toString(i)).setSource("field", Integer.toString(i)).execute().actionGet();
        }
        refresh();
        SearchResponse searchResponse = client().prepareSearch("test").setQuery(matchAllQuery()).addSort(SortBuilders.fieldSort("field").order(SortOrder.ASC)).execute().actionGet();
        assertThat(searchResponse.getHits().getAt(0).sortValues()[0].toString(), equalTo("1"));
        assertThat(searchResponse.getHits().getAt(1).sortValues()[0].toString(), equalTo("10"));
        assertThat(searchResponse.getHits().getAt(2).sortValues()[0].toString(), equalTo("100"));
        
        // reindex and refresh
        client().prepareIndex("test", "type", Integer.toString(1)).setSource("field", Integer.toString(1)).execute().actionGet();
        refresh();

        searchResponse = client().prepareSearch("test").setQuery(matchAllQuery()).addSort(SortBuilders.fieldSort("field").order(SortOrder.ASC)).execute().actionGet();
        assertThat(searchResponse.getHits().getAt(0).sortValues()[0].toString(), equalTo("1"));
        assertThat(searchResponse.getHits().getAt(1).sortValues()[0].toString(), equalTo("10"));
        assertThat(searchResponse.getHits().getAt(2).sortValues()[0].toString(), equalTo("100"));
        
        // reindex - no refresh
        client().prepareIndex("test", "type", Integer.toString(1)).setSource("field", Integer.toString(1)).execute().actionGet();

        searchResponse = client().prepareSearch("test").setQuery(matchAllQuery()).addSort(SortBuilders.fieldSort("field").order(SortOrder.ASC)).execute().actionGet();
        assertThat(searchResponse.getHits().getAt(0).sortValues()[0].toString(), equalTo("1"));
        assertThat(searchResponse.getHits().getAt(1).sortValues()[0].toString(), equalTo("10"));
        assertThat(searchResponse.getHits().getAt(2).sortValues()[0].toString(), equalTo("100"));
        
        // optimize
        optimize();
        refresh();

        client().prepareIndex("test", "type", Integer.toString(1)).setSource("field", Integer.toString(1)).execute().actionGet();
        searchResponse = client().prepareSearch("test").setQuery(matchAllQuery()).addSort(SortBuilders.fieldSort("field").order(SortOrder.ASC)).execute().actionGet();
        assertThat(searchResponse.getHits().getAt(0).sortValues()[0].toString(), equalTo("1"));
        assertThat(searchResponse.getHits().getAt(1).sortValues()[0].toString(), equalTo("10"));
        assertThat(searchResponse.getHits().getAt(2).sortValues()[0].toString(), equalTo("100"));
        
        refresh();
        searchResponse = client().prepareSearch("test").setQuery(matchAllQuery()).addSort(SortBuilders.fieldSort("field").order(SortOrder.ASC)).execute().actionGet();
        assertThat(searchResponse.getHits().getAt(0).sortValues()[0].toString(), equalTo("1"));
        assertThat(searchResponse.getHits().getAt(1).sortValues()[0].toString(), equalTo("10"));
        assertThat(searchResponse.getHits().getAt(2).sortValues()[0].toString(), equalTo("100"));
    }

    @Test
    public void testScoreSortDirection() throws Exception {
        prepareCreate("test").setSettings(randomSettingsBuilder().put("index.number_of_shards", 1)).execute().actionGet();
        ensureGreen();

        client().prepareIndex("test", "type", "1").setSource("field", 2).execute().actionGet();
        client().prepareIndex("test", "type", "2").setSource("field", 1).execute().actionGet();
        client().prepareIndex("test", "type", "3").setSource("field", 0).execute().actionGet();

        refresh();

        SearchResponse searchResponse = client().prepareSearch("test").setQuery(customScoreQuery(matchAllQuery()).script("_source.field")).execute().actionGet();
        assertThat(searchResponse.getHits().getAt(0).getId(), equalTo("1"));
        assertThat(searchResponse.getHits().getAt(1).score(), Matchers.lessThan(searchResponse.getHits().getAt(0).score()));
        assertThat(searchResponse.getHits().getAt(1).getId(), equalTo("2"));
        assertThat(searchResponse.getHits().getAt(2).score(), Matchers.lessThan(searchResponse.getHits().getAt(1).score()));
        assertThat(searchResponse.getHits().getAt(2).getId(), equalTo("3"));

        searchResponse = client().prepareSearch("test").setQuery(customScoreQuery(matchAllQuery()).script("_source.field")).addSort("_score", SortOrder.DESC).execute().actionGet();
        assertThat(searchResponse.getHits().getAt(0).getId(), equalTo("1"));
        assertThat(searchResponse.getHits().getAt(1).score(), Matchers.lessThan(searchResponse.getHits().getAt(0).score()));
        assertThat(searchResponse.getHits().getAt(1).getId(), equalTo("2"));
        assertThat(searchResponse.getHits().getAt(2).score(), Matchers.lessThan(searchResponse.getHits().getAt(1).score()));
        assertThat(searchResponse.getHits().getAt(2).getId(), equalTo("3"));

        searchResponse = client().prepareSearch("test").setQuery(customScoreQuery(matchAllQuery()).script("_source.field")).addSort("_score", SortOrder.DESC).execute().actionGet();
        assertThat(searchResponse.getHits().getAt(2).getId(), equalTo("3"));
        assertThat(searchResponse.getHits().getAt(1).getId(), equalTo("2"));
        assertThat(searchResponse.getHits().getAt(0).getId(), equalTo("1"));
    }

    @Test
    public void testIssue2986() {
        prepareCreate("test").setSettings(getSettings()).execute().actionGet();

        client().prepareIndex("test", "post", "1").setSource("{\"field1\":\"value1\"}").execute().actionGet();
        client().prepareIndex("test", "post", "2").setSource("{\"field1\":\"value2\"}").execute().actionGet();
        client().prepareIndex("test", "post", "3").setSource("{\"field1\":\"value3\"}").execute().actionGet();
        refresh();
        SearchResponse result = client().prepareSearch("test").setQuery(matchAllQuery()).setTrackScores(true).addSort("field1", SortOrder.ASC).execute().actionGet();

        for (SearchHit hit : result.getHits()) {
            assert !Float.isNaN(hit.getScore());
        }
    }

    @Test
    public void testIssue2991() {
        for (int i = 1; i < 4; i++) {
            try {
                client().admin().indices().prepareDelete("test").execute().actionGet();
            } catch (Exception e) {
                // ignore
            }
            prepareCreate("test").setSettings(ImmutableSettings.settingsBuilder().put("index.number_of_shards", i).put("index.number_of_replicas", 0)).execute().actionGet();
            ensureGreen();
            client().prepareIndex("test", "type", "1").setSource("tag", "alpha").execute().actionGet();
            refresh();

            client().prepareIndex("test", "type", "3").setSource("tag", "gamma").execute().actionGet();
            refresh();

            client().prepareIndex("test", "type", "4").setSource("tag", "delta").execute().actionGet();
            
            refresh();
            client().prepareIndex("test", "type", "2").setSource("tag", "beta").execute().actionGet();
            
            refresh();
            SearchResponse resp = client().prepareSearch("test").setSize(2).setQuery(matchAllQuery()).addSort(SortBuilders.fieldSort("tag").order(SortOrder.ASC)).execute().actionGet();
            assertHitCount(resp, 4);
            assertThat(resp.getHits().hits().length, equalTo(2));
            assertFirstHit(resp, hasId("1"));
            assertSecondHit(resp, hasId("2"));
            
            resp = client().prepareSearch("test").setSize(2).setQuery(matchAllQuery()).addSort(SortBuilders.fieldSort("tag").order(SortOrder.DESC)).execute().actionGet();
            assertHitCount(resp, 4);
            assertThat(resp.getHits().hits().length, equalTo(2));
            assertFirstHit(resp, hasId("3"));
            assertSecondHit(resp, hasId("4"));
        }
    }

    @Test
    public void testSimpleSortsSingleShard() throws Exception {
        testSimpleSorts(1);
    }

    @Test
    public void testSimpleSortsTwoShards() throws Exception {
        testSimpleSorts(2);
    }

    @Test
    public void testSimpleSortsThreeShards() throws Exception {
        testSimpleSorts(3);
    }

    private void testSimpleSorts(int numberOfShards) throws Exception {
        final long seed = System.currentTimeMillis();
        logger.info("testSimpleSorts SEED:[{}]", seed);
        Random random = new Random(seed);
        prepareCreate("test")
                .setSettings(randomSettingsBuilder().put("index.number_of_shards", numberOfShards).put("index.number_of_replicas", 0))
                .addMapping("type1", XContentFactory.jsonBuilder().startObject().startObject("type1").startObject("properties")
                        .startObject("str_value").field("type", "string").endObject()
                        .startObject("boolean_value").field("type", "boolean").endObject()
                        .startObject("byte_value").field("type", "byte").endObject()
                        .startObject("short_value").field("type", "short").endObject()
                        .startObject("integer_value").field("type", "integer").endObject()
                        .startObject("long_value").field("type", "long").endObject()
                        .startObject("float_value").field("type", "float").endObject()
                        .startObject("double_value").field("type", "double").endObject()
                        .endObject().endObject().endObject())
                .execute().actionGet();
        ensureGreen();
        List<IndexRequestBuilder> builders = new ArrayList<IndexRequestBuilder>();
        for (int i = 0; i < 10; i++) {
            IndexRequestBuilder builder = client().prepareIndex("test", "type1", Integer.toString(i)).setSource(jsonBuilder().startObject()
                    .field("str_value", new String(new char[]{(char) (97 + i), (char) (97 + i)}))
                    .field("boolean_value", true)
                    .field("byte_value", i)
                    .field("short_value", i)
                    .field("integer_value", i)
                    .field("long_value", i)
                    .field("float_value", 0.1 * i)
                    .field("double_value", 0.1 * i)
                    .endObject());
            builders.add(builder);
        }
        Collections.shuffle(builders, random);
        for (IndexRequestBuilder builder : builders) {
            builder.execute().actionGet();
            if (random.nextBoolean()) {
                if (random.nextInt(5) != 0) {
                    refresh();
                } else {
                  client().admin().indices().prepareFlush().execute().actionGet();
                }
            }
            
        }
        refresh();

        // STRING
        int size = 1 + random.nextInt(10);

        SearchResponse searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("str_value", SortOrder.ASC)
                .execute().actionGet();
        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(i)));
            assertThat(searchResponse.getHits().getAt(i).sortValues()[0].toString(), equalTo(new String(new char[]{(char) (97 + i), (char) (97 + i)})));
        }
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("str_value", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(9 - i)));
            assertThat(searchResponse.getHits().getAt(i).sortValues()[0].toString(), equalTo(new String(new char[]{(char) (97 + (9 - i)), (char) (97 + (9 - i))})));
        }

        assertThat(searchResponse.toString(), not(containsString("error")));

        // BYTE
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("byte_value", SortOrder.ASC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(i)));
            assertThat(((Number) searchResponse.getHits().getAt(i).sortValues()[0]).byteValue(), equalTo((byte) i));
        }
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("byte_value", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(9 - i)));
            assertThat(((Number) searchResponse.getHits().getAt(i).sortValues()[0]).byteValue(), equalTo((byte) (9 - i)));
        }

        assertThat(searchResponse.toString(), not(containsString("error")));

        // SHORT
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("short_value", SortOrder.ASC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(i)));
            assertThat(((Number) searchResponse.getHits().getAt(i).sortValues()[0]).shortValue(), equalTo((short) i));
        }
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("short_value", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(9 - i)));
            assertThat(((Number) searchResponse.getHits().getAt(i).sortValues()[0]).shortValue(), equalTo((short) (9 - i)));
        }

        assertThat(searchResponse.toString(), not(containsString("error")));

        // INTEGER
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("integer_value", SortOrder.ASC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(i)));
            assertThat(((Number) searchResponse.getHits().getAt(i).sortValues()[0]).intValue(), equalTo((int) i));
        }

        assertThat(searchResponse.toString(), not(containsString("error")));
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("integer_value", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(9 - i)));
            assertThat(((Number) searchResponse.getHits().getAt(i).sortValues()[0]).intValue(), equalTo((int) (9 - i)));
        }

        assertThat(searchResponse.toString(), not(containsString("error")));

        // LONG
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("long_value", SortOrder.ASC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(i)));
            assertThat(((Number) searchResponse.getHits().getAt(i).sortValues()[0]).longValue(), equalTo((long) i));
        }

        assertThat(searchResponse.toString(), not(containsString("error")));
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("long_value", SortOrder.DESC)
                .execute().actionGet();
        assertHitCount(searchResponse, 10l);
        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(9 - i)));
            assertThat(((Number) searchResponse.getHits().getAt(i).sortValues()[0]).longValue(), equalTo((long) (9 - i)));
        }

        assertThat(searchResponse.toString(), not(containsString("error")));

        // FLOAT
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("float_value", SortOrder.ASC)
                .execute().actionGet();

        assertHitCount(searchResponse, 10l);
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(i)));
            assertThat(((Number) searchResponse.getHits().getAt(i).sortValues()[0]).doubleValue(), closeTo(0.1d * i, 0.000001d));
        }

        assertThat(searchResponse.toString(), not(containsString("error")));
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("float_value", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(10l));
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(9 - i)));
            assertThat(((Number) searchResponse.getHits().getAt(i).sortValues()[0]).doubleValue(), closeTo(0.1d * (9 - i), 0.000001d));
        }

        assertThat(searchResponse.toString(), not(containsString("error")));

        // DOUBLE
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("double_value", SortOrder.ASC)
                .execute().actionGet();

        assertHitCount(searchResponse, 10l);
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(i)));
            assertThat(((Number) searchResponse.getHits().getAt(i).sortValues()[0]).doubleValue(), closeTo(0.1d * i, 0.000001d));
        }

        assertThat(searchResponse.toString(), not(containsString("error")));
        size = 1 + random.nextInt(10);
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(size)
                .addSort("double_value", SortOrder.DESC)
                .execute().actionGet();

        assertHitCount(searchResponse, 10l);
        assertThat(searchResponse.getHits().hits().length, equalTo(size));
        for (int i = 0; i < size; i++) {
            assertThat(searchResponse.getHits().getAt(i).id(), equalTo(Integer.toString(9 - i)));
            assertThat(((Number) searchResponse.getHits().getAt(i).sortValues()[0]).doubleValue(), closeTo(0.1d * (9 - i), 0.000001d));
        }

        assertNoFailures(searchResponse);
    }
    
    @Test 
    public void testSortScript() throws IOException {
       createIndexMapped("test", "test", "value", "string");
        ensureGreen();
        for (int i = 0; i < 10; i++) {
            client().prepareIndex("test", "test", "1").setSource(jsonBuilder().startObject()
                    .field("value", "" + i).endObject()).execute().actionGet();
        }
        refresh();
        SearchResponse searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addSort(SortBuilders.scriptSort("\u0027\u0027", "string")).setSize(10)
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.getShardFailures()), searchResponse.getShardFailures().length, equalTo(0));
    }
    
    @Test
    public void testSortMinValueScript() throws IOException {
        String mapping = jsonBuilder().startObject().startObject("type1").startObject("properties")
                .startObject("lvalue").field("type", "long").endObject()
                .startObject("dvalue").field("type", "double").endObject()
                .startObject("svalue").field("type", "string").endObject()
                .startObject("gvalue").field("type", "geo_point").endObject()
                .endObject().endObject().endObject().string();
        prepareCreate("test").setSettings(getSettings()).addMapping("type1", mapping).execute().actionGet();
        ensureGreen();

        for (int i = 0; i < 10; i++) {
            IndexRequestBuilder req = client().prepareIndex("test", "type1", ""+i).setSource(jsonBuilder().startObject()
                    .field("ord", i)
                    .field("svalue", new String[]{""+i, ""+(i+1), ""+(i+2)})
                    .field("lvalue", new long[] {i, i+1, i+2})
                    .field("dvalue", new double[] {i, i+1, i+2})
                    .startObject("gvalue")
                        .startObject("location")
                             .field("lat", (double)i+1)
                             .field("lon", (double)i)
                        .endObject()
                    .endObject()
                    .endObject());
            req.execute().actionGet();
        }
        
        for (int i = 10; i < 20; i++) { // add some docs that don't have values in those fields
            client().prepareIndex("test", "type1", ""+i).setSource(jsonBuilder().startObject()
                    .field("ord", i)
            .endObject()).execute().actionGet();
        }
        client().admin().indices().prepareRefresh("test").execute().actionGet();
        
        // test the long values
        SearchResponse searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addScriptField("min", "var retval = Long.MAX_VALUE; for (v : doc['lvalue'].values){  retval = Math.min(v, retval);} return retval;")
                .addSort("ord", SortOrder.ASC).setSize(10)
                .execute().actionGet();

        assertThat("Failures " + Arrays.toString(searchResponse.getShardFailures()), searchResponse.getShardFailures().length, equalTo(0));

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(20l));
        for (int i = 0; i < 10; i++) {
            assertThat("res: " + i + " id: " + searchResponse.getHits().getAt(i).getId(), (Long)searchResponse.getHits().getAt(i).field("min").value(), equalTo((long)i));
        }
        // test the double values
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addScriptField("min", "var retval = Double.MAX_VALUE; for (v : doc['dvalue'].values){  retval = Math.min(v, retval);} return retval;")
                .addSort("ord", SortOrder.ASC).setSize(10)
                .execute().actionGet();

        assertThat("Failures " + Arrays.toString(searchResponse.getShardFailures()), searchResponse.getShardFailures().length, equalTo(0));

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(20l));
        for (int i = 0; i < 10; i++) {
            assertThat("res: " + i + " id: " + searchResponse.getHits().getAt(i).getId(), (Double)searchResponse.getHits().getAt(i).field("min").value(), equalTo((double)i));
        }
        
        // test the string values
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addScriptField("min", "var retval = Integer.MAX_VALUE; for (v : doc['svalue'].values){  retval = Math.min(Integer.parseInt(v), retval);} return retval;")
                .addSort("ord", SortOrder.ASC).setSize(10)
                .execute().actionGet();

        assertThat("Failures " + Arrays.toString(searchResponse.getShardFailures()), searchResponse.getShardFailures().length, equalTo(0));

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(20l));
        for (int i = 0; i < 10; i++) {
            assertThat("res: " + i + " id: " + searchResponse.getHits().getAt(i).getId(), (Integer)searchResponse.getHits().getAt(i).field("min").value(), equalTo(i));
        }
        
        // test the geopoint values
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addScriptField("min", "var retval = Double.MAX_VALUE; for (v : doc['gvalue'].values){  retval = Math.min(v.lon, retval);} return retval;")
                .addSort("ord", SortOrder.ASC).setSize(10)
                .execute().actionGet();

        assertThat("Failures " + Arrays.toString(searchResponse.getShardFailures()), searchResponse.getShardFailures().length, equalTo(0));

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(20l));
        for (int i = 0; i < 10; i++) {
            assertThat("res: " + i + " id: " + searchResponse.getHits().getAt(i).getId(), (Double)searchResponse.getHits().getAt(i).field("min").value(), equalTo((double)i));
        }
    }

    @Test
    public void testDocumentsWithNullValue() throws Exception {
        // TODO: sort shouldn't fail when sort field is mapped dynamically
        // We have to specify mapping explicitly because by the time search is performed dynamic mapping might not
        // be propagated to all nodes yet and sort operation fail when the sort field is not defined
        String mapping = jsonBuilder().startObject().startObject("type1").startObject("properties")
                .startObject("svalue").field("type", "string").endObject()
                .endObject().endObject().endObject().string();
        prepareCreate("test").setSettings(getSettings()).addMapping("type1", mapping).execute().actionGet();
        ensureGreen();

        client().prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("id", "1")
                .field("svalue", "aaa")
                .endObject()).execute().actionGet();

        client().prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("id", "2")
                .nullField("svalue")
                .endObject()).execute().actionGet();

        client().prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("id", "3")
                .field("svalue", "bbb")
                .endObject()).execute().actionGet();


        client().admin().indices().prepareFlush().setRefresh(true).execute().actionGet();

        SearchResponse searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addScriptField("id", "doc['id'].value")
                .addSort("svalue", SortOrder.ASC)
                .execute().actionGet();

        assertThat("Failures " + Arrays.toString(searchResponse.getShardFailures()), searchResponse.getShardFailures().length, equalTo(0));

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat((String) searchResponse.getHits().getAt(0).field("id").value(), equalTo("2"));
        assertThat((String) searchResponse.getHits().getAt(1).field("id").value(), equalTo("1"));
        assertThat((String) searchResponse.getHits().getAt(2).field("id").value(), equalTo("3"));
        
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addScriptField("id", "doc['id'].values[0]")
                .addSort("svalue", SortOrder.ASC)
                .execute().actionGet();

        assertThat("Failures " + Arrays.toString(searchResponse.getShardFailures()), searchResponse.getShardFailures().length, equalTo(0));

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat((String) searchResponse.getHits().getAt(0).field("id").value(), equalTo("2"));
        assertThat((String) searchResponse.getHits().getAt(1).field("id").value(), equalTo("1"));
        assertThat((String) searchResponse.getHits().getAt(2).field("id").value(), equalTo("3"));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addScriptField("id", "doc['id'].value")
                .addSort("svalue", SortOrder.DESC)
                .execute().actionGet();

        if (searchResponse.getFailedShards() > 0) {
            logger.warn("Failed shards:");
            for (ShardSearchFailure shardSearchFailure : searchResponse.getShardFailures()) {
                logger.warn("-> {}", shardSearchFailure);
            }
        }
        assertThat(searchResponse.getFailedShards(), equalTo(0));

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat((String) searchResponse.getHits().getAt(0).field("id").value(), equalTo("3"));
        assertThat((String) searchResponse.getHits().getAt(1).field("id").value(), equalTo("1"));
        assertThat((String) searchResponse.getHits().getAt(2).field("id").value(), equalTo("2"));

        // a query with docs just with null values
        searchResponse = client().prepareSearch()
                .setQuery(termQuery("id", "2"))
                .addScriptField("id", "doc['id'].value")
                .addSort("svalue", SortOrder.DESC)
                .execute().actionGet();

        if (searchResponse.getFailedShards() > 0) {
            logger.warn("Failed shards:");
            for (ShardSearchFailure shardSearchFailure : searchResponse.getShardFailures()) {
                logger.warn("-> {}", shardSearchFailure);
            }
        }
        assertThat(searchResponse.getFailedShards(), equalTo(0));

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(1l));
        assertThat((String) searchResponse.getHits().getAt(0).field("id").value(), equalTo("2"));
    }

    @Test
    public void testSortMissing() throws Exception {
        prepareCreate("test").addMapping("type1",
                XContentFactory.jsonBuilder()
                .startObject()
                    .startObject("type1")
                        .startObject("properties")
                            .startObject("i_value")
                                .field("type", "integer")
                            .endObject()
                            .startObject("d_value")
                                .field("type", "float")
                            .endObject()
                        .endObject()
                    .endObject()
                .endObject()).execute().actionGet();
        ensureGreen();
        client().prepareIndex("test", "type1", "1").setSource(jsonBuilder().startObject()
                .field("id", "1")
                .field("i_value", -1)
                .field("d_value", -1.1)
                .endObject()).execute().actionGet();

        client().prepareIndex("test", "type1", "2").setSource(jsonBuilder().startObject()
                .field("id", "2")
                .endObject()).execute().actionGet();

        client().prepareIndex("test", "type1", "3").setSource(jsonBuilder().startObject()
                .field("id", "1")
                .field("i_value", 2)
                .field("d_value", 2.2)
                .endObject()).execute().actionGet();

        client().admin().indices().prepareFlush().setRefresh(true).execute().actionGet();

        logger.info("--> sort with no missing (same as missing _last)");
        SearchResponse searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addSort(SortBuilders.fieldSort("i_value").order(SortOrder.ASC))
                .execute().actionGet();
        assertThat(Arrays.toString(searchResponse.getShardFailures()), searchResponse.getFailedShards(), equalTo(0));

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("1"));
        assertThat(searchResponse.getHits().getAt(1).id(), equalTo("3"));
        assertThat(searchResponse.getHits().getAt(2).id(), equalTo("2"));

        logger.info("--> sort with missing _last");
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addSort(SortBuilders.fieldSort("i_value").order(SortOrder.ASC).missing("_last"))
                .execute().actionGet();
        assertThat(Arrays.toString(searchResponse.getShardFailures()), searchResponse.getFailedShards(), equalTo(0));

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("1"));
        assertThat(searchResponse.getHits().getAt(1).id(), equalTo("3"));
        assertThat(searchResponse.getHits().getAt(2).id(), equalTo("2"));

        logger.info("--> sort with missing _first");
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addSort(SortBuilders.fieldSort("i_value").order(SortOrder.ASC).missing("_first"))
                .execute().actionGet();
        assertThat(Arrays.toString(searchResponse.getShardFailures()), searchResponse.getFailedShards(), equalTo(0));

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("2"));
        assertThat(searchResponse.getHits().getAt(1).id(), equalTo("1"));
        assertThat(searchResponse.getHits().getAt(2).id(), equalTo("3"));
    }

    @Test
    public void testIgnoreUnmapped() throws Exception {
        createIndex("test");

        client().prepareIndex("test", "type1", "1").setSource(jsonBuilder().startObject()
                .field("id", "1")
                .field("i_value", -1)
                .field("d_value", -1.1)
                .endObject()).execute().actionGet();
        ensureYellow();
        logger.info("--> sort with an unmapped field, verify it fails");
        try {
                SearchResponse result = client().prepareSearch()
                    .setQuery(matchAllQuery())
                    .addSort(SortBuilders.fieldSort("kkk"))
                    .execute().actionGet();
                assertThat("Expected exception but returned with", result, nullValue());
        } catch (SearchPhaseExecutionException e) {
        }
        ensureYellow();
        SearchResponse searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .addSort(SortBuilders.fieldSort("kkk").ignoreUnmapped(true))
                .execute().actionGet();
        assertNoFailures(searchResponse);
    }

    @Test
    public void testSortMVField() throws Exception {
        prepareCreate("test")
                .setSettings(randomSettingsBuilder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0))
                .addMapping("type1", XContentFactory.jsonBuilder().startObject().startObject("type1").startObject("properties")
                        .startObject("long_values").field("type", "long").endObject()
                        .startObject("int_values").field("type", "integer").endObject()
                        .startObject("short_values").field("type", "short").endObject()
                        .startObject("byte_values").field("type", "byte").endObject()
                        .startObject("float_values").field("type", "float").endObject()
                        .startObject("double_values").field("type", "double").endObject()
                        .startObject("string_values").field("type", "string").field("index", "not_analyzed").endObject()
                        .endObject().endObject().endObject())
                .execute().actionGet();
        ensureGreen();
        ensureGreen();

        client().prepareIndex("test", "type1", Integer.toString(1)).setSource(jsonBuilder().startObject()
                .array("long_values", 1l, 5l, 10l, 8l)
                .array("int_values", 1, 5, 10, 8)
                .array("short_values", 1, 5, 10, 8)
                .array("byte_values", 1, 5, 10, 8)
                .array("float_values", 1f, 5f, 10f, 8f)
                .array("double_values", 1d, 5d, 10d, 8d)
                .array("string_values", "01", "05", "10", "08")
                .endObject()).execute().actionGet();
        client().prepareIndex("test", "type1", Integer.toString(2)).setSource(jsonBuilder().startObject()
                .array("long_values", 11l, 15l, 20l, 7l)
                .array("int_values", 11, 15, 20, 7)
                .array("short_values", 11, 15, 20, 7)
                .array("byte_values", 11, 15, 20, 7)
                .array("float_values", 11f, 15f, 20f, 7f)
                .array("double_values", 11d, 15d, 20d, 7d)
                .array("string_values", "11", "15", "20", "07")
                .endObject()).execute().actionGet();
        client().prepareIndex("test", "type1", Integer.toString(3)).setSource(jsonBuilder().startObject()
                .array("long_values", 2l, 1l, 3l, -4l)
                .array("int_values", 2, 1, 3, -4)
                .array("short_values", 2, 1, 3, -4)
                .array("byte_values", 2, 1, 3, -4)
                .array("float_values", 2f, 1f, 3f, -4f)
                .array("double_values", 2d, 1d, 3d, -4d)
                .array("string_values", "02", "01", "03", "!4")
                .endObject()).execute().actionGet();

        refresh();

        SearchResponse searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("long_values", SortOrder.ASC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).longValue(), equalTo(-4l));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).longValue(), equalTo(1l));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).longValue(), equalTo(7l));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("long_values", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).longValue(), equalTo(20l));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).longValue(), equalTo(10l));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).longValue(), equalTo(3l));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort(SortBuilders.fieldSort("long_values").order(SortOrder.DESC).sortMode("sum"))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).longValue(), equalTo(53l));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).longValue(), equalTo(24l));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).longValue(), equalTo(2l));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("int_values", SortOrder.ASC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).intValue(), equalTo(-4));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).intValue(), equalTo(1));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).intValue(), equalTo(7));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("int_values", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).intValue(), equalTo(20));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).intValue(), equalTo(10));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).intValue(), equalTo(3));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("short_values", SortOrder.ASC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).intValue(), equalTo(-4));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).intValue(), equalTo(1));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).intValue(), equalTo(7));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("short_values", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).intValue(), equalTo(20));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).intValue(), equalTo(10));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).intValue(), equalTo(3));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("byte_values", SortOrder.ASC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).intValue(), equalTo(-4));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).intValue(), equalTo(1));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).intValue(), equalTo(7));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("byte_values", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).intValue(), equalTo(20));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).intValue(), equalTo(10));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).intValue(), equalTo(3));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("float_values", SortOrder.ASC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).floatValue(), equalTo(-4f));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).floatValue(), equalTo(1f));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).floatValue(), equalTo(7f));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("float_values", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).floatValue(), equalTo(20f));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).floatValue(), equalTo(10f));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).floatValue(), equalTo(3f));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("double_values", SortOrder.ASC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).doubleValue(), equalTo(-4d));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).doubleValue(), equalTo(1d));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).doubleValue(), equalTo(7d));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("double_values", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(2)));
        assertThat(((Number) searchResponse.getHits().getAt(0).sortValues()[0]).doubleValue(), equalTo(20d));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Number) searchResponse.getHits().getAt(1).sortValues()[0]).doubleValue(), equalTo(10d));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(3)));
        assertThat(((Number) searchResponse.getHits().getAt(2).sortValues()[0]).doubleValue(), equalTo(3d));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("string_values", SortOrder.ASC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(3)));
        assertThat(((Text) searchResponse.getHits().getAt(0).sortValues()[0]).string(), equalTo("!4"));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Text) searchResponse.getHits().getAt(1).sortValues()[0]).string(), equalTo("01"));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(2)));
        assertThat(((Text) searchResponse.getHits().getAt(2).sortValues()[0]).string(), equalTo("07"));

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(10)
                .addSort("string_values", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(3l));
        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(2)));
        assertThat(((Text) searchResponse.getHits().getAt(0).sortValues()[0]).string(), equalTo("20"));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Text) searchResponse.getHits().getAt(1).sortValues()[0]).string(), equalTo("10"));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(3)));
        assertThat(((Text) searchResponse.getHits().getAt(2).sortValues()[0]).string(), equalTo("03"));
    }
    
    @Test
    public void testSortOnRareField() throws ElasticSearchException, IOException {
        prepareCreate("test")
                .setSettings(randomSettingsBuilder().put("index.number_of_shards", 1).put("index.number_of_replicas", 0))
                .addMapping("type1", XContentFactory.jsonBuilder().startObject().startObject("type1").startObject("properties")
                        .startObject("string_values").field("type", "string").field("index", "not_analyzed").endObject()
                        .endObject().endObject().endObject())
                .execute().actionGet();
        ensureGreen();
        client().prepareIndex("test", "type1", Integer.toString(1)).setSource(jsonBuilder().startObject()
                .array("string_values", "01", "05", "10", "08")
                .endObject()).execute().actionGet();

        
       
       
        refresh();
        SearchResponse searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(3)
                .addSort("string_values", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().hits().length, equalTo(1));


        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(1)));
        assertThat(((Text) searchResponse.getHits().getAt(0).sortValues()[0]).string(), equalTo("10"));
        
        client().prepareIndex("test", "type1", Integer.toString(2)).setSource(jsonBuilder().startObject()
                .array("string_values", "11", "15", "20", "07")
                .endObject()).execute().actionGet();
        for (int i = 0; i < 15; i++) {
            client().prepareIndex("test", "type1", Integer.toString(300+i)).setSource(jsonBuilder().startObject()
                    .array("some_other_field", "foobar")
                    .endObject()).execute().actionGet();
        }
        refresh();

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(2)
                .addSort("string_values", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().hits().length, equalTo(2));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(2)));
        assertThat(((Text) searchResponse.getHits().getAt(0).sortValues()[0]).string(), equalTo("20"));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Text) searchResponse.getHits().getAt(1).sortValues()[0]).string(), equalTo("10"));

        
        client().prepareIndex("test", "type1", Integer.toString(3)).setSource(jsonBuilder().startObject()
                .array("string_values", "02", "01", "03", "!4")
                .endObject()).execute().actionGet();
        for (int i = 0; i < 15; i++) {
            client().prepareIndex("test", "type1", Integer.toString(300+i)).setSource(jsonBuilder().startObject()
                    .array("some_other_field", "foobar")
                    .endObject()).execute().actionGet();
        }
        refresh();

        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(3)
                .addSort("string_values", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(2)));
        assertThat(((Text) searchResponse.getHits().getAt(0).sortValues()[0]).string(), equalTo("20"));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Text) searchResponse.getHits().getAt(1).sortValues()[0]).string(), equalTo("10"));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(3)));
        assertThat(((Text) searchResponse.getHits().getAt(2).sortValues()[0]).string(), equalTo("03"));
        
        for (int i = 0; i < 15; i++) {
            client().prepareIndex("test", "type1", Integer.toString(300+i)).setSource(jsonBuilder().startObject()
                    .array("some_other_field", "foobar")
                    .endObject()).execute().actionGet();
            refresh();
        }
        
        searchResponse = client().prepareSearch()
                .setQuery(matchAllQuery())
                .setSize(3)
                .addSort("string_values", SortOrder.DESC)
                .execute().actionGet();

        assertThat(searchResponse.getHits().hits().length, equalTo(3));

        assertThat(searchResponse.getHits().getAt(0).id(), equalTo(Integer.toString(2)));
        assertThat(((Text) searchResponse.getHits().getAt(0).sortValues()[0]).string(), equalTo("20"));

        assertThat(searchResponse.getHits().getAt(1).id(), equalTo(Integer.toString(1)));
        assertThat(((Text) searchResponse.getHits().getAt(1).sortValues()[0]).string(), equalTo("10"));

        assertThat(searchResponse.getHits().getAt(2).id(), equalTo(Integer.toString(3)));
        assertThat(((Text) searchResponse.getHits().getAt(2).sortValues()[0]).string(), equalTo("03"));
    }

}
