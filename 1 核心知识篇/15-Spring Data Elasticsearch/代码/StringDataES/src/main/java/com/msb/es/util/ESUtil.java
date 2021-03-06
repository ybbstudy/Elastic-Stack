package com.msb.es.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.github.pagehelper.PageInfo;
import com.msb.es.dto.Document;
import com.msb.es.dto.EsDataId;
import com.msb.es.dto.enums.FieldType;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.client.indices.PutMappingRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.elasticsearch.search.fetch.subphase.highlight.HighlightField;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

@Slf4j
@Component
public class ESUtil {
    @Resource
    private RestHighLevelClient restHighLevelClient;

    private static int index_number_of_shards = 3;//???????????????

    private static int index_number_of_replicas = 1;//???????????????  ?????????

    public void setIndexNumber(int index_number_of_shards, int index_number_of_replicas) {
        this.index_number_of_shards = index_number_of_shards;
        this.index_number_of_replicas = index_number_of_replicas;
    }

    public RestHighLevelClient getInstance() {
        return restHighLevelClient;
    }

    //region ????????????(??????????????????3???????????????1)
    /**
     * ????????????(??????????????????1???????????????0)
     *
     * @param clazz ????????????????????????es??????
     * @throws IOException
     */
    public boolean createIndex(Class clazz) throws Exception {
        Document declaredAnnotation = (Document) clazz.getDeclaredAnnotation(Document.class);
        if (declaredAnnotation == null) {
            throw new Exception(String.format("class name: %s can not find Annotation [Document], please check", clazz.getName()));
        }
        String indexName = declaredAnnotation.indexName();
        boolean flag = createRootIndex(indexName, clazz);
        if (flag) {
            return true;
        }
        return false;
    }

    /**
     * ????????????(??????????????????5???????????????1)
     *
     * @param clazz ????????????????????????es??????
     * @throws IOException
     */
    public boolean createIndexIfNotExist(Class clazz) throws Exception {
        Document declaredAnnotation = (Document) clazz.getDeclaredAnnotation(Document.class);
        if (declaredAnnotation == null) {
            throw new Exception(String.format("class name: %s can not find Annotation [Document], please check", clazz.getName()));
        }
        String indexName = declaredAnnotation.indexName();

        boolean indexExists = isIndexExists(indexName);
        if (!indexExists) {
            boolean flag = createRootIndex(indexName, clazz);
            if (flag) {
                return true;
            }
        }
        return false;
    }

    private boolean createRootIndex(String indexName, Class clazz) throws IOException {
        CreateIndexRequest request = new CreateIndexRequest(indexName);
        request.settings(Settings.builder()
                // ?????????????????? ?????????
                .put("index.number_of_shards", index_number_of_shards)
                .put("index.number_of_replicas", index_number_of_replicas)
        );
        request.mapping(generateBuilder(clazz));
        CreateIndexResponse response = restHighLevelClient.indices().create(request, RequestOptions.DEFAULT);
        // ??????????????????????????????????????????
        boolean acknowledged = response.isAcknowledged();
        // ???????????????????????????????????????????????????????????????????????????????????????
        boolean shardsAcknowledged = response.isShardsAcknowledged();
        return acknowledged || shardsAcknowledged;
    }
    //endregion

    //region ????????????
    /**
     * ????????????(??????????????????5???????????????1)???
     * ????????????????????????????????????????????????
     * ??????????????????????????????
     *
     * @param clazz ????????????????????????es??????
     * @throws IOException
     */
    public boolean updateIndex(Class clazz) throws Exception {
        Document declaredAnnotation = (Document) clazz.getDeclaredAnnotation(Document.class);
        if (declaredAnnotation == null) {
            throw new Exception(String.format("class name: %s can not find Annotation [Document], please check", clazz.getName()));
        }
        String indexName = declaredAnnotation.indexName();
        PutMappingRequest request = new PutMappingRequest(indexName);

        request.source(generateBuilder(clazz));
        AcknowledgedResponse response = restHighLevelClient.indices().putMapping(request, RequestOptions.DEFAULT);
        // ??????????????????????????????????????????
        boolean acknowledged = response.isAcknowledged();

        if (acknowledged) {
            return true;
        }
        return false;
    }
    //endregion

    //region ????????????
    /**
     * ????????????
     *
     * @param indexName
     * @return
     */
    public boolean delIndex(String indexName) {
        boolean acknowledged = false;
        try {
            DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(indexName);
            deleteIndexRequest.indicesOptions(IndicesOptions.LENIENT_EXPAND_OPEN);
            AcknowledgedResponse delete = restHighLevelClient.indices().delete(deleteIndexRequest, RequestOptions.DEFAULT);
            acknowledged = delete.isAcknowledged();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return acknowledged;
    }
    //endregion

    //region ????????????????????????
    /**
     * ????????????????????????
     *
     * @param indexName
     * @return
     */
    public boolean isIndexExists(String indexName) {
        boolean exists = false;
        try {
            GetIndexRequest getIndexRequest = new GetIndexRequest(indexName);
            getIndexRequest.humanReadable(true);
            exists = restHighLevelClient.indices().exists(getIndexRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return exists;
    }
    //endregion

    //region ??????????????????
    /**
     * ??????????????????
     * ?????????????????????
     * 1. json
     * 2. map
     * Map<String, Object> jsonMap = new HashMap<>();
     * jsonMap.put("user", "kimchy");
     * jsonMap.put("postDate", new Date());
     * jsonMap.put("message", "trying out Elasticsearch");
     * IndexRequest indexRequest = new IndexRequest("posts")
     * .id("1").source(jsonMap);
     * 3. builder
     * XContentBuilder builder = XContentFactory.jsonBuilder();
     * builder.startObject();
     * {
     * builder.field("user", "kimchy");
     * builder.timeField("postDate", new Date());
     * builder.field("message", "trying out Elasticsearch");
     * }
     * builder.endObject();
     * IndexRequest indexRequest = new IndexRequest("posts")
     * .id("1").source(builder);
     * 4. source:
     * IndexRequest indexRequest = new IndexRequest("posts")
     * .id("1")
     * .source("user", "kimchy",
     * "postDate", new Date(),
     * "message", "trying out Elasticsearch");
     * <p>
     * ?????????  Validation Failed: 1: type is missing;
     * ????????????jar?????????
     * <p>
     * ??????????????????????????????
     *
     * @return
     */
    public IndexResponse index(Object o) throws Exception {
        Document declaredAnnotation = (Document) o.getClass().getDeclaredAnnotation(Document.class);
        if (declaredAnnotation == null) {
            throw new Exception(String.format("class name: %s can not find Annotation [Document], please check", o.getClass().getName()));
        }
        String indexName = declaredAnnotation.indexName();

        IndexRequest request = new IndexRequest(indexName);
        Field fieldByAnnotation = getFieldByAnnotation(o, EsDataId.class);
        if (fieldByAnnotation != null) {
            fieldByAnnotation.setAccessible(true);
            try {
                Object id = fieldByAnnotation.get(o);
                request = request.id(id.toString());
            } catch (IllegalAccessException e) {
            }
        }

        String userJson = JSON.toJSONString(o);
        request.source(userJson, XContentType.JSON);
        IndexResponse indexResponse = restHighLevelClient.index(request, RequestOptions.DEFAULT);
        return indexResponse;
    }
    //endregion

    //region queryById
    /**
     * ??????id??????
     *
     * @return
     */
    public String queryById(String indexName, String id) throws IOException {
        GetRequest getRequest = new GetRequest(indexName, id);
        // getRequest.fetchSourceContext(FetchSourceContext.DO_NOT_FETCH_SOURCE);

        GetResponse getResponse = restHighLevelClient.get(getRequest, RequestOptions.DEFAULT);
        String jsonStr = getResponse.getSourceAsString();
        return jsonStr;
    }
    //endregion

    //region ??????????????????json?????????
    /**
     * ??????????????????json?????????
     *
     * @param indexName
     * @param searchSourceBuilder
     * @return
     * @throws IOException
     */
    public String search(String indexName, SearchSourceBuilder searchSourceBuilder) throws IOException {
        SearchRequest searchRequest = new SearchRequest(indexName);
        searchRequest.source(searchSourceBuilder);
        searchRequest.scroll(TimeValue.timeValueMinutes(1L));
        SearchResponse searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
        String scrollId = searchResponse.getScrollId();
        SearchHits hits = searchResponse.getHits();
        JSONArray jsonArray = new JSONArray();
        for (SearchHit hit : hits) {
            String sourceAsString = hit.getSourceAsString();
            JSONObject jsonObject = JSON.parseObject(sourceAsString);
            jsonArray.add(jsonObject);
        }
        return jsonArray.toJSONString();
    }
    //endregion

    //region ????????????????????????
    /**
     * ????????????????????????
     *
     * @param searchSourceBuilder
     * @param pageNum
     * @param pageSize
     * @param s
     * @param <T>
     * @return
     * @throws IOException
     */
    public <T> PageInfo<T> search(SearchSourceBuilder searchSourceBuilder, int pageNum, int pageSize, Class<T> s) throws Exception {
        Document declaredAnnotation = (Document) s.getDeclaredAnnotation(Document.class);
        if (declaredAnnotation == null) {
            throw new Exception(String.format("class name: %s can not find Annotation [Document], please check", s.getName()));
        }
        String indexName = declaredAnnotation.indexName();
        SearchRequest searchRequest = new SearchRequest(indexName);
        searchRequest.source(searchSourceBuilder);
        SearchResponse searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);
        SearchHits hits = searchResponse.getHits();
        JSONArray jsonArray = new JSONArray();
        for (SearchHit hit : hits) {
            String sourceAsString = hit.getSourceAsString();
            JSONObject jsonObject = JSON.parseObject(sourceAsString);
            jsonArray.add(jsonObject);
        }
        int total = (int) hits.getTotalHits().value;

        // ????????????
        List<T> list = jsonArray.toJavaList(s);
        PageInfo<T> page = new PageInfo<>();
        page.setList(list);
        page.setPageNum(pageNum);
        page.setPageSize(pageSize);
        page.setTotal(total);
        page.setPages(total == 0 ? 0 : (total % pageSize == 0 ? total / pageSize : (total / pageSize) + 1));
        page.setHasNextPage(page.getPageNum() < page.getPages());
        return page;
    }
    //endregion

    //region ???????????????????????????
    /**
     * ???????????????????????????
     *
     * @param searchSourceBuilder
     * @param s
     * @param <T>
     * @return
     * @throws IOException
     */
    public <T> List<T> search(SearchSourceBuilder searchSourceBuilder, Class<T> s) throws Exception {
        Document declaredAnnotation = s.getDeclaredAnnotation(Document.class);
        if (declaredAnnotation == null) {
            throw new Exception(String.format("class name: %s can not find Annotation [Document], please check", s.getName()));
        }
        String indexName = declaredAnnotation.indexName();
        SearchRequest searchRequest = new SearchRequest(indexName);
        searchRequest.source(searchSourceBuilder);
        searchRequest.scroll(TimeValue.timeValueMinutes(1L));
        SearchResponse searchResponse = restHighLevelClient.search(searchRequest, RequestOptions.DEFAULT);

        // //????????????????????????
        // HighlightBuilder highlightBuilder = new HighlightBuilder(); //?????????????????????
        // highlightBuilder.field(title);      //??????????????????
        // highlightBuilder.field(content);    //??????????????????
        // highlightBuilder.requireFieldMatch(false);     //???????????????????????????,????????????false
        // highlightBuilder.preTags("<span style=\"color:red\">");   //????????????
        // highlightBuilder.postTags("</span>");
        //
        // //???????????????,?????????????????????????????????????????????????????????,????????????,???????????????????????????,?????????????????????
        // highlightBuilder.fragmentSize(800000); //?????????????????????
        // highlightBuilder.numOfFragments(0); //????????????????????????????????????

        String scrollId = searchResponse.getScrollId();
        SearchHits hits = searchResponse.getHits();
        JSONArray jsonArray = new JSONArray();
        for (SearchHit hit : hits) {
            String sourceAsString = hit.getSourceAsString();
            JSONObject jsonObject = JSON.parseObject(sourceAsString);
            jsonArray.add(jsonObject);
        }
        // ????????????
        List<T> list = jsonArray.toJavaList(s);
        return list;
    }
    //endregion

    //region ??????????????????
    /**
     * ??????????????????
     * ???????????? ?????????
     * ??????????????? ?????????
     *
     * @param list
     * @return
     */
    public <T> boolean batchSaveOrUpdate(List<T> list, boolean izAsync) throws Exception {
        Object o1 = list.get(0);
        Document declaredAnnotation = (Document) o1.getClass().getDeclaredAnnotation(Document.class);
        if (declaredAnnotation == null) {
            throw new Exception(String.format("class name: %s can not find Annotation [@Document], please check", o1.getClass().getName()));
        }
        String indexName = declaredAnnotation.indexName();

        BulkRequest request = new BulkRequest(indexName);
        for (Object o : list) {
            String jsonStr = JSON.toJSONString(o);
            IndexRequest indexReq = new IndexRequest().source(jsonStr, XContentType.JSON);

            Field fieldByAnnotation = getFieldByAnnotation(o, EsDataId.class);
            if (fieldByAnnotation != null) {
                fieldByAnnotation.setAccessible(true);
                try {
                    Object id = fieldByAnnotation.get(o);
                    indexReq = indexReq.id(id.toString());
                } catch (IllegalAccessException e) {
                }
            }
            request.add(indexReq);
        }
        if (izAsync) {
            BulkResponse bulkResponse = restHighLevelClient.bulk(request, RequestOptions.DEFAULT);
            return outResult(bulkResponse);
        } else {
            restHighLevelClient.bulkAsync(request, RequestOptions.DEFAULT, new ActionListener<BulkResponse>() {
                @Override
                public void onResponse(BulkResponse bulkResponse) {
                    outResult(bulkResponse);
                }

                @Override
                public void onFailure(Exception e) {
                }
            });
        }
        return true;
    }
    //endregion

    //region ????????????
    /**
     * ????????????
     *
     * @param indexName??? ????????????
     * @param docId???     ??????id
     */
    public boolean deleteDoc(String indexName, String docId) throws IOException {
        DeleteRequest request = new DeleteRequest(indexName, docId);
        DeleteResponse deleteResponse = restHighLevelClient.delete(request, RequestOptions.DEFAULT);
        // ??????response
        String index = deleteResponse.getIndex();
        String id = deleteResponse.getId();
        long version = deleteResponse.getVersion();
        ReplicationResponse.ShardInfo shardInfo = deleteResponse.getShardInfo();
        if (shardInfo.getFailed() > 0) {
            for (ReplicationResponse.ShardInfo.Failure failure :
                    shardInfo.getFailures()) {
                String reason = failure.reason();
            }
        }
        return true;
    }
    //endregion

    //region ??????json??????????????????
    /**
     * ??????json??????????????????
     *
     * @param indexName
     * @param docId
     * @param o
     * @return
     * @throws IOException
     */
    public boolean updateDoc(String indexName, String docId, Object o) throws IOException {
        UpdateRequest request = new UpdateRequest(indexName, docId);
        request.doc(JSON.toJSONString(o), XContentType.JSON);
        UpdateResponse updateResponse = restHighLevelClient.update(request, RequestOptions.DEFAULT);
        String index = updateResponse.getIndex();
        String id = updateResponse.getId();
        long version = updateResponse.getVersion();
        if (updateResponse.getResult() == DocWriteResponse.Result.CREATED) {
            return true;
        } else if (updateResponse.getResult() == DocWriteResponse.Result.UPDATED) {
            return true;
        } else if (updateResponse.getResult() == DocWriteResponse.Result.DELETED) {
        } else if (updateResponse.getResult() == DocWriteResponse.Result.NOOP) {
        }
        return false;
    }
    //endregion

    //region ??????Map??????????????????
    /**
     * ??????Map??????????????????
     *
     * @param indexName
     * @param docId
     * @param map
     * @return
     * @throws IOException
     */
    public boolean updateDoc(String indexName, String docId, Map<String, Object> map) throws IOException {
        UpdateRequest request = new UpdateRequest(indexName, docId);
        request.doc(map);
        UpdateResponse updateResponse = restHighLevelClient.update(request, RequestOptions.DEFAULT);
        String index = updateResponse.getIndex();
        String id = updateResponse.getId();
        long version = updateResponse.getVersion();
        if (updateResponse.getResult() == DocWriteResponse.Result.CREATED) {
            return true;
        } else if (updateResponse.getResult() == DocWriteResponse.Result.UPDATED) {
            return true;
        } else if (updateResponse.getResult() == DocWriteResponse.Result.DELETED) {
        } else if (updateResponse.getResult() == DocWriteResponse.Result.NOOP) {

        }
        return false;
    }
    //endregion

    //region generateBuilder
    public XContentBuilder generateBuilder(Class clazz) throws IOException {
        // ???????????????????????????
        Document doc = (Document) clazz.getAnnotation(Document.class);
        System.out.println(doc.indexName());

        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        builder.startObject("properties");
        Field[] declaredFields = clazz.getDeclaredFields();
        for (Field f : declaredFields) {
            if (f.isAnnotationPresent(com.msb.es.dto.Field.class)) {
                // ????????????
                com.msb.es.dto.Field declaredAnnotation =
                        f.getDeclaredAnnotation(com.msb.es.dto.Field.class);

                // ?????????????????????
                /**
                 * {
                 *   "mappings": {
                 *     "properties": {
                 *       "region": {
                 *         "type": "keyword"
                 *       },
                 *       "manager": {
                 *         "properties": {
                 *           "age":  { "type": "integer" },
                 *           "name": {
                 *             "properties": {
                 *               "first": { "type": "text" },
                 *               "last":  { "type": "text" }
                 *             }
                 *           }
                 *         }
                 *       }
                 *     }
                 *   }
                 * }
                 */
                if (declaredAnnotation.type() == FieldType.OBJECT) {
                    // ????????????????????????-- Action
                    Class<?> type = f.getType();
                    Field[] df2 = type.getDeclaredFields();
                    builder.startObject(f.getName());
                    builder.startObject("properties");
                    // ?????????????????????????????????
                    for (Field f2 : df2) {
                        if (f2.isAnnotationPresent(com.msb.es.dto.Field.class)) {
                            // ????????????
                            com.msb.es.dto.Field declaredAnnotation2 = f2.getDeclaredAnnotation(com.msb.es.dto.Field.class);
                            builder.startObject(f2.getName());
                            builder.field("type", declaredAnnotation2.type().getType());
                            // keyword???????????????
                            if (declaredAnnotation2.type() == FieldType.TEXT) {
                                builder.field("analyzer", declaredAnnotation2.analyzer().getType());
                            }
                            if (declaredAnnotation2.type() == FieldType.DATE) {
                                builder.field("format", "yyyy-MM-dd HH:mm:ss");
                            }
                            builder.endObject();
                        }
                    }
                    builder.endObject();
                    builder.endObject();

                } else {
                    builder.startObject(f.getName());
                    builder.field("type", declaredAnnotation.type().getType());
                    // keyword???????????????
                    if (declaredAnnotation.type() == FieldType.TEXT) {
                        builder.field("analyzer", declaredAnnotation.analyzer().getType());
                    }
                    if (declaredAnnotation.type() == FieldType.DATE) {
                        builder.field("format", "yyyy-MM-dd HH:mm:ss");
                    }
                    builder.endObject();
                }
            }
        }
        // ??????property
        builder.endObject();
        builder.endObject();
        return builder;
    }
    //endregion

    //region getFieldByAnnotation
    public static Field getFieldByAnnotation(Object o, Class annotationClass) {
        Field[] declaredFields = o.getClass().getDeclaredFields();
        if (declaredFields != null && declaredFields.length > 0) {
            for (Field f : declaredFields) {
                if (f.isAnnotationPresent(annotationClass)) {
                    return f;
                }
            }
        }
        return null;
    }
    //endregion

    //region getLowLevelClient
    /**
     * getLowLevelClient
     *
     * @return
     */
    public RestClient getLowLevelClient() {
        return restHighLevelClient.getLowLevelClient();
    }
    //endregion

    //region ??????????????? ????????????
    /**
     * ??????????????? ????????????
     * map????????? JSONObject.parseObject(JSONObject.toJSONString(map), Content.class)
     *
     * @param searchResponse
     * @param highlightField
     */
    public List<Map<String, Object>> setSearchResponse(SearchResponse searchResponse, String highlightField) {
        //????????????
        ArrayList<Map<String, Object>> list = new ArrayList<>();
        for (SearchHit hit : searchResponse.getHits().getHits()) {
            Map<String, HighlightField> high = hit.getHighlightFields();
            HighlightField title = high.get(highlightField);

            hit.getSourceAsMap().put("id", hit.getId());

            Map<String, Object> sourceAsMap = hit.getSourceAsMap();//???????????????
            //??????????????????,????????????????????????????????????
            if (title != null) {
                Text[] texts = title.fragments();
                String nTitle = "";
                for (Text text : texts) {
                    nTitle += text;
                }
                //??????
                sourceAsMap.put(highlightField, nTitle);
            }
            list.add(sourceAsMap);
        }
        return list;
    }
    //endregion

    //region ???????????????
    /**
     * ???????????????
     *
     * @param index          ????????????
     * @param query          ????????????
     * @param size           ??????????????????
     * @param from           ??????????????????
     * @param fields         ???????????????????????????????????????????????????????????????
     * @param sortField      ????????????
     * @param highlightField ????????????
     * @return
     */
    public List<Map<String, Object>> searchListData(String index,
                                                    SearchSourceBuilder query,
                                                    Integer size,
                                                    Integer from,
                                                    String fields,
                                                    String sortField,
                                                    String highlightField) throws IOException {
        SearchRequest request = new SearchRequest(index);
        SearchSourceBuilder builder = query;
        if (StringUtils.isNotEmpty(fields)) {
            //???????????????????????????????????????????????????????????????????????????
            builder.fetchSource(new FetchSourceContext(true, fields.split(","), Strings.EMPTY_ARRAY));
        }
        from = from <= 0 ? 0 : from * size;
        //???????????????????????????????????????????????????from??????????????????0
        builder.from(from);
        builder.size(size);
        if (StringUtils.isNotEmpty(sortField)) {
            //???????????????????????????proposal_no???text?????????????????????keyword?????????????????????.keyword
            builder.sort(sortField + ".keyword", SortOrder.ASC);
        }
        //??????
        HighlightBuilder highlight = new HighlightBuilder();
        highlight.field(highlightField);
        //??????????????????
        highlight.requireFieldMatch(false);
        highlight.preTags("<span style='color:red'>");
        highlight.postTags("</span>");
        builder.highlighter(highlight);
        //???????????????????????????????????????????????????
        //builder.fetchSource(false);
        request.source(builder);
        SearchResponse response = restHighLevelClient.search(request, RequestOptions.DEFAULT);
        if (response.status().getStatus() == 200) {
            // ????????????
            return setSearchResponse(response, highlightField);
        }
        return null;
    }
    //endregion

    private boolean outResult(BulkResponse bulkResponse) {
        for (BulkItemResponse bulkItemResponse : bulkResponse) {
            DocWriteResponse itemResponse = bulkItemResponse.getResponse();
            IndexResponse indexResponse = (IndexResponse) itemResponse;
            if (bulkItemResponse.isFailed()) {
                return false;
            }
        }
        return true;
    }
}
