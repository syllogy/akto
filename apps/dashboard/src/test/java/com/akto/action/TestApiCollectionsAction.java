package com.akto.action;

import com.akto.MongoBasedTest;
import com.akto.dao.ApiCollectionsDao;
import com.akto.dao.ApiInfoDao;
import com.akto.dto.ApiCollection;
import com.akto.dto.ApiInfo;
import com.akto.dto.ApiInfo.ApiInfoKey;
import com.akto.dto.type.URLMethods.Method;
import com.mongodb.BasicDBObject;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class TestApiCollectionsAction extends MongoBasedTest {

    private void validate(String name) {
        List<ApiCollection> apiCollectionList = ApiCollectionsDao.instance.findAll(new BasicDBObject());

        if (name == null) {
            assertEquals(apiCollectionList.size(), 0);
            return;
        } else {
            assertEquals(apiCollectionList.size(), 1);
        }

        assertEquals(apiCollectionList.get(0).getName(), name);

    }

    @Test
    public void testHappy() {
        ApiCollectionsDao.instance.getMCollection().drop();
        ApiCollectionsAction apiCollectionsAction = new ApiCollectionsAction();
        String name = "Avneesh.123-_";
        apiCollectionsAction.setCollectionName(name);
        String result = apiCollectionsAction.createCollection();
        assertEquals(result, "SUCCESS");
        validate(name);
    }

    @Test
    public void testMaxSize() {
        ApiCollectionsDao.instance.getMCollection().drop();
        ApiCollectionsAction apiCollectionsAction = new ApiCollectionsAction();
        String name = "Avneesh.123-_33333333333333333333333333333333333333333333";
        apiCollectionsAction.setCollectionName(name);
        String result = apiCollectionsAction.createCollection();
        assertEquals(result, "ERROR");
        validate(null);
    }

    @Test
    public void testInvalidChars() {
        ApiCollectionsDao.instance.getMCollection().drop();
        ApiCollectionsAction apiCollectionsAction = new ApiCollectionsAction();
        String name = "Avneesh#123";
        apiCollectionsAction.setCollectionName(name);
        String result = apiCollectionsAction.createCollection();
        assertEquals(result, "ERROR");
        validate(null);
    }

    @Test
    public void testUniqueCollectionName() {
        ApiCollectionsDao.instance.getMCollection().drop();
        ApiCollectionsAction apiCollectionsAction = new ApiCollectionsAction();
        String name = "Avneesh123";
        apiCollectionsAction.setCollectionName(name);
        apiCollectionsAction.createCollection();
        validate(name);

        apiCollectionsAction.setCollectionName(name);
        String result = apiCollectionsAction.createCollection();
        validate(name);
        assertEquals(result, "ERROR");
    }

    @Test
    public void fetchAllCollections() {
        ApiCollectionsDao.instance.getMCollection().drop();
        List<ApiCollection> apiCollectionList = new ArrayList<>();

        // mirroring collection with host
        Set<String> urls1 = new HashSet<>(Arrays.asList("1", "2", "3", "4", "5", "6"));
        apiCollectionList.add(new ApiCollection(1000, "one", 1000, urls1, "one.com", 1000, false, true));

        // mirroring collections without hosts
        Set<String> urls2 = new HashSet<>(Arrays.asList("1", "2", "3"));
        apiCollectionList.add(new ApiCollection(2000, "two", 2000, urls2, null,2000, false, true));

        // manually created collections
        Set<String> urls3 = new HashSet<>(Arrays.asList("1", "2", "3", "4"));
        apiCollectionList.add(new ApiCollection(3000, "three", 3000, urls3, null,0, false, true));

        ApiCollectionsDao.instance.insertMany(apiCollectionList);
        Method method = Method.GET;

        List<ApiInfo> apiInfos = new ArrayList<>();
        for (int c=1; c<4; c++) {
            int apiCollectionId = c*1000;
            for (int i = 0; i < 100; i++) {
                String url = "/api/v1" + i;
                ApiInfo apiInfo= new ApiInfo(
                    new ApiInfoKey(apiCollectionId, url, method)
                );
                apiInfos.add(apiInfo);
            }
        }

        ApiInfoDao.instance.insertMany(apiInfos);

        ApiCollectionsAction apiCollectionsAction = new ApiCollectionsAction();
        apiCollectionsAction.fetchAllCollections();
        List<ApiCollection> apiCollections = apiCollectionsAction.apiCollections;

        assertEquals(3,apiCollections.size());

        Map<Integer, ApiCollection> apiCollectionMap = new HashMap<>();
        for (ApiCollection apiCollection: apiCollections)  {
            apiCollectionMap.put(apiCollection.getId(), apiCollection);
        }

        assertEquals(100, apiCollectionMap.get(1000).getUrlsCount());
        assertEquals(3, apiCollectionMap.get(2000).getUrlsCount()); // because burp collection we use count from urls stored in set
        assertEquals(4, apiCollectionMap.get(3000).getUrlsCount()); // because burp collection we use count from urls stored in set

    }
}
