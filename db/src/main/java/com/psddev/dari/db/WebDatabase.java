package com.psddev.dari.db;

import com.psddev.dari.util.ObjectUtils;
import com.psddev.dari.util.PaginatedResult;
import com.psddev.dari.util.PullThroughCache;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.http.NameValuePair;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;

/** Database backed by {@linkplain WebDatabaseServlet web APIs}. */
public class WebDatabase extends AbstractDatabase<Void> {

    public static final String REMOTE_URL_SUB_SETTING = "remoteUrl";
    public static final String REMOTE_DATABASE_SUB_SETTING = "remoteDatabase";

    public static final String ACTION_PARAMETER = "action";
    public static final String DATABASE_PARAMETER = "database";
    public static final String QUERY_PARAMETER = "query";
    public static final String OFFSET_PARAMETER = "offset";
    public static final String LIMIT_PARAMETER = "limit";
    public static final String SAVES_PARAMETER = "saves";
    public static final String INDEXES_PARAMETER = "indexes";
    public static final String DELETES_PARAMETER = "deletes";

    public static final String READ_ALL_ACTION = "readAll";
    public static final String READ_ALL_GROUPED_ACTION = "readAllGrouped";
    public static final String READ_COUNT_ACTION = "readCount";
    public static final String READ_FIRST_ACTION = "readFirst";
    public static final String READ_LAST_UPDATE_ACTION = "readLastUpdate";
    public static final String READ_PARTIAL_ACTION = "readPartial";
    public static final String READ_PARTIAL_GROUPED_ACTION = "readPartialGrouped";
    public static final String WRITE_ACTION = "write";

    public static final String STATUS_KEY = "status";
    public static final String RESULT_KEY = "result";

    public static final String OK_STATUS = "ok";
    public static final String ERROR_STATUS = "error";

    private String remoteUrl;
    private String remoteDatabase;
    private Credentials credentials;

    /** Returns the remote URL. */
    public String getRemoteUrl() {
        return remoteUrl;
    }

    /** Sets the remote URL. */
    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    /** Returns the remote database name. */
    public String getRemoteDatabase() {
        return remoteDatabase;
    }

    /** Sets the remote database name. */
    public void setRemoteDatabase(String remoteDatabase) {
        this.remoteDatabase = remoteDatabase;
    }

    public Credentials getCredentials() {
        return credentials;
    }

    public void setCredentials(Credentials credentials) {
        this.credentials = credentials;
    }

    // --- AbstractDatabase support ---

    private static final Map<WebDatabase, DatabaseEnvironment> ENVIRONMENT_CACHE = new PullThroughCache<WebDatabase, DatabaseEnvironment>() {
        @Override
        protected DatabaseEnvironment produce(WebDatabase database) {
            return new DatabaseEnvironment(database, false);
        }
    };

    @Override
    public DatabaseEnvironment getEnvironment() {
        return ENVIRONMENT_CACHE.get(this);
    }

    @Override
    protected void doInitialize(String settingsKey, Map<String, Object> settings) {
        setRemoteUrl(ObjectUtils.to(String.class, settings.get(REMOTE_URL_SUB_SETTING)));
        setRemoteDatabase(ObjectUtils.to(String.class, settings.get(REMOTE_DATABASE_SUB_SETTING)));
    }

    @Override
    public Void openConnection() {
        return null;
    }

    @Override
    public void closeConnection(Void connection) {
    }

    private List<NameValuePair> createParameters(String action, Query<?> query) {
        List<NameValuePair> params = new ArrayList<NameValuePair>();
        params.add(new BasicNameValuePair(ACTION_PARAMETER, action));

        String database = getRemoteDatabase();
        if (database != null) {
            params.add(new BasicNameValuePair(DATABASE_PARAMETER, database));
        }

        if (query != null) {
            Map<String, Object> queryMap = query.getState().getSimpleValues();
            convertTypeIdToName(queryMap);
            params.add(new BasicNameValuePair(QUERY_PARAMETER, ObjectUtils.toJson(queryMap)));
        }

        return params;
    }

    @SuppressWarnings("unchecked")
    private void convertTypeIdToName(Map<?, Object> map) {
        for (Map.Entry<?, Object> entry : map.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();

            if (StateValueUtils.TYPE_KEY.equals(key)) {
                UUID typeId = ObjectUtils.to(UUID.class, value);
                if (typeId != null) {
                    entry.setValue(ObjectType.getInstance(typeId).getInternalName());
                }

            } else if (value instanceof Map) {
                convertTypeIdToName((Map<?, Object>) value);

            } else if (value instanceof List) {
                for (Object item : (List<?>) value) {
                    if (item instanceof Map) {
                        convertTypeIdToName((Map<?, Object>) item);
                    }
                }
            }
        }
    }

    private Object sendRequest(List<NameValuePair> params) {
        String response;
        DefaultHttpClient client = new DefaultHttpClient();
        
        if(this.getCredentials() != null){
            client.getCredentialsProvider().setCredentials(AuthScope.ANY, this.getCredentials());
        }
        
        try {
            HttpPost post = new HttpPost(getRemoteUrl());
            post.setEntity(new UrlEncodedFormEntity(params, HTTP.UTF_8));
            response = client.execute(post, new BasicResponseHandler());

        } catch (Exception ex) {
            throw new DatabaseException(this, ex);

        } finally {
            client.getConnectionManager().shutdown();
        }

        Object responseObject = ObjectUtils.fromJson(response);
        if (!(responseObject instanceof Map)) {
            throw new DatabaseException(this, String.format(
                    "Server didn't return a valid response! (%s)",
                    response));
        }

        Map<?, ?> responseMap = (Map<?, ?>) responseObject;
        if (OK_STATUS.equals(responseMap.get(STATUS_KEY))) {
            return responseMap.get(RESULT_KEY);
        }

        String message = ObjectUtils.to(String.class, responseMap.get(RESULT_KEY));
        throw new DatabaseException(this, ObjectUtils.isBlank(message) ?
                String.format("Unknown error! (%s)", response) :
                message);
    }

    private <T> T createSavedObjectWithMap(Object mapObject, Query<T> query) {
        if (mapObject == null) {
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, ?> map = (Map<String, ?>) mapObject;
        T object = createSavedObject(
                map.get(StateValueUtils.TYPE_KEY),
                map.get(StateValueUtils.ID_KEY),
                query);
        State.getInstance(object).putAll(map);
        return swapObjectType(query, object);
    }

    @Override
    public <T> List<T> readAll(Query<T> query) {
        List<NameValuePair> params = createParameters(READ_ALL_ACTION, query);
        List<T> objects = new ArrayList<T>();

        for (Object item : (List<?>) sendRequest(params)) {
            objects.add(createSavedObjectWithMap(item, query));
        }

        return objects;
    }

    @Override
    public long readCount(Query<?> query) {
        List<NameValuePair> params = createParameters(READ_COUNT_ACTION, query);
        return ObjectUtils.to(long.class, sendRequest(params));
    }

    @Override
    public <T> T readFirst(Query<T> query) {
        List<NameValuePair> params = createParameters(READ_FIRST_ACTION, query);
        return createSavedObjectWithMap(sendRequest(params), query);
    }

    @Override
    public Date readLastUpdate(Query<?> query) {
        List<NameValuePair> params = createParameters(READ_LAST_UPDATE_ACTION, query);
        return ObjectUtils.to(Date.class, sendRequest(params));
    }

    @Override
    public <T> PaginatedResult<T> readPartial(Query<T> query, long offset, int limit) {
        List<NameValuePair> params = createParameters(READ_PARTIAL_ACTION, query);
        params.add(new BasicNameValuePair(OFFSET_PARAMETER, String.valueOf(offset)));
        params.add(new BasicNameValuePair(LIMIT_PARAMETER, String.valueOf(limit)));

        Map<?, ?> result = (Map<?, ?>) sendRequest(params);
        List<T> objects = new ArrayList<T>();

        for (Object item : (List<?>) result.get("items")) {
            objects.add(createSavedObjectWithMap(item, query));
        }

        return new PaginatedResult<T>(
                offset,
                limit,
                ObjectUtils.to(long.class, result.get("count")),
                objects);
    }

    private void addWriteParameters(List<NameValuePair> params, String name, List<State> states) {
        for (State state : states) {
            params.add(new BasicNameValuePair(name, ObjectUtils.toJson(state.getSimpleValues())));
        }
    }

    @Override
    protected void doWrites(Void connection, boolean isImmediate, List<State> saves, List<State> indexes, List<State> deletes) {
        List<NameValuePair> params = createParameters(WRITE_ACTION, null);
        addWriteParameters(params, SAVES_PARAMETER, saves);
        addWriteParameters(params, INDEXES_PARAMETER, indexes);
        addWriteParameters(params, DELETES_PARAMETER, deletes);
        sendRequest(params);
    }

    // --- Object support ---

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (other instanceof WebDatabase) {
            WebDatabase otherDatabase = (WebDatabase) other;
            return ObjectUtils.equals(getRemoteUrl(), otherDatabase.getRemoteUrl()) &&
                    ObjectUtils.equals(getRemoteDatabase(), otherDatabase.getRemoteDatabase());
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return ObjectUtils.hashCode(getRemoteUrl(), getRemoteDatabase());
    }
}
