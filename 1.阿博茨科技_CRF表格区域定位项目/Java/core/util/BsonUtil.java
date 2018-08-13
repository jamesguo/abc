package com.abcft.pdfextract.core.util;

import com.google.gson.*;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.lang.reflect.Field;
import java.util.*;

/**
 * Created by dhu on 2017/5/25.
 */
public class BsonUtil {

    private static final String OBJECT_ID_KEY = "$oid";
    private static final String DATE_KEY = "$date";

    public static String getString(Document document, String key, String defaultValue) {
        if (document.containsKey(key)) {
            return document.getString(key);
        } else {
            return defaultValue;
        }
    }

    public static int getInteger(Document document, String key) {
        return getInteger(document, key, 0);
    }

    public static int getInteger(Document document, String key, int defaultValue) {
        if (document.containsKey(key)) {
            Object value = document.get(key);
            if (value instanceof Integer) {
                return (Integer)value;
            } else if (value instanceof Long) {
                return (int)(long)value;
            } else if (value instanceof Double) {
                return (int)(double)value;
            } else if (value instanceof Float) {
                return (int)(float)value;
            } else if (null == value) {
                return defaultValue;
            } else {
                try {
                    return Integer.parseInt(String.valueOf(value));
                } catch (Exception e) {
                    return defaultValue;
                }
            }
        } else {
            return defaultValue;
        }
    }


    public static Document toDocument(JsonObject jsonObject) {
        Document document = new Document();
        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            String key = entry.getKey();
            JsonElement element = entry.getValue();
            if (element instanceof JsonArray) {
                document.put(key, toDocument((JsonArray) element));
            } else if (element instanceof JsonObject) {
                // 支持FileId
                FileId fileId = deserializeFileId(element);
                if (fileId != null) {
                    fileId.appendToDocument(document, key);
                } else {
                    document.put(key, toDocument((JsonObject) element));
                }
            } else if (element instanceof JsonPrimitive) {
                document.put(key, getJsonPrimitiveValue((JsonPrimitive) element));
            } else {
                document.put(key, null);
            }
        }
        return document;
    }

    public static List<Object> toDocument(JsonArray jsonObject) {
        List<Object> list = new ArrayList<>();
        jsonObject.forEach(element -> {
            if (element instanceof JsonArray) {
                list.add(toDocument((JsonArray) element));
            } else if (element instanceof JsonObject) {
                list.add(toDocument((JsonObject) element));
            } else if (element instanceof JsonPrimitive) {
                list.add(getJsonPrimitiveValue((JsonPrimitive) element));
            } else {
                list.add(null);
            }
        });
        return list;
    }

    public static JsonElement serializeObject(Object userData) {
        if (null == userData || userData instanceof JsonNull) {
            return JsonNull.INSTANCE;
        } else if (userData instanceof JsonElement) {
            // Either JsonArray, JsonObject or JsonPrimitive
            return (JsonElement)userData;
        } else if (userData instanceof Number) {
            return new JsonPrimitive((Number)userData);
        } else if (userData instanceof CharSequence) {
            return new JsonPrimitive(userData.toString());
        } else if (userData instanceof Boolean) {
            return new JsonPrimitive((Boolean)userData);
        } else if (userData instanceof Character) {
            return new JsonPrimitive((Character)userData);
        } else if (userData instanceof FileId) {
            return serializeFileId((FileId)userData);
        } else if (userData instanceof ObjectId) {
            return serializeObjectId((ObjectId)userData);
        } else if (userData instanceof Date) {
            return serializeDate((Date)userData);
        } else {
            throw new IllegalArgumentException("Unable to serialize " + userData + " as json.");
        }
    }

    public static JsonElement serializeFileId(FileId fileId) {
        if (fileId instanceof FileId.MongoObjectId) {
            ObjectId objectId = ((FileId.MongoObjectId)fileId).value();
            return serializeObjectId(objectId);
        } else if (fileId instanceof FileId.NumberId) {
            long id = ((FileId.NumberId)fileId).value();
            return new JsonPrimitive(id);
        } else if (fileId instanceof FileId.DateId) {
            Date value = ((FileId.DateId)fileId).value();
            return serializeDate(value);
        } else {
            return new JsonPrimitive(fileId.toString());
        }
    }

    private static JsonElement serializeObjectId(ObjectId objectId) {
        JsonObject oid = new JsonObject();
        oid.addProperty(OBJECT_ID_KEY, objectId.toHexString());
        return oid;
    }

    public static JsonElement serializeDate(Date value) {
        JsonObject date = new JsonObject();
        date.addProperty(DATE_KEY, value.getTime());
        return date;
    }

    public static Date deserializeDate(JsonElement value) {
        if (value instanceof JsonObject && ((JsonObject) value).has(DATE_KEY)) {
            long time = ((JsonObject) value).get(DATE_KEY).getAsLong();
            return new Date(time);
        } else {
            return null;
        }
    }

    public static FileId deserializeFileId(JsonElement value) {
        if (value instanceof JsonObject) {
            JsonObject valueObj = (JsonObject) value;
            if (valueObj.has(OBJECT_ID_KEY)) {
                String oid = valueObj.get(OBJECT_ID_KEY).getAsString();
                return FileId.fromObject(new ObjectId(oid));
            } else if (valueObj.has(DATE_KEY)) {
                long time = valueObj.get(DATE_KEY).getAsLong();
                return FileId.fromObject(new Date(time));
            }
        } else if (value instanceof JsonPrimitive) {
            JsonPrimitive simpleValue = (JsonPrimitive)value;
            if (simpleValue.isString()) {
                return FileId.fromObject(simpleValue.getAsString());
            } else if (simpleValue.isNumber()) {
                return FileId.fromObject(simpleValue.getAsLong());
            }
        }
        return null;
    }

    private static Field sJsonPrimitive_value = null;
    private static Object getJsonPrimitiveValue(JsonPrimitive jsonPrimitive) {
        if (sJsonPrimitive_value == null) {
            try {
                sJsonPrimitive_value = JsonPrimitive.class.getDeclaredField("value");
                sJsonPrimitive_value.setAccessible(true);
            } catch (Exception ignored) {
            }
        }
        if (sJsonPrimitive_value != null) {
            try {
                return sJsonPrimitive_value.get(jsonPrimitive);
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    public static JsonObject toJson(Document document) {
        JsonObject jsonObject = new JsonObject();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String key = entry.getKey();
            Object element = entry.getValue();
            if (element instanceof String) {
                jsonObject.addProperty(key, (String) element);
            } else if (element instanceof Character) {
                jsonObject.addProperty(key, (Character) element);
            } else if (element instanceof Number) {
                jsonObject.addProperty(key, (Number) element);
            } else if (element instanceof Boolean) {
                jsonObject.addProperty(key, (Boolean) element);
            } else if (element instanceof Collection) {
                jsonObject.add(key, toJson((Collection<?>) element));
            } else if (element instanceof Document) {
                jsonObject.add(key, toJson((Document) element));
            } else if (element instanceof Date) {
                jsonObject.add(key, serializeDate((Date) element));
            } else if (element instanceof ObjectId) {
                jsonObject.add(key, serializeObjectId((ObjectId) element));
            } else if (element != null) {
                jsonObject.addProperty(key, element.toString());
            } else {
                //jsonObject.add(key, JsonNull.INSTANCE);
            }
        }
        return jsonObject;
    }

    public static JsonArray toJson(Collection<?> values) {
        JsonArray jsonArray = new JsonArray();
        for (Object element : values) {
            if (element instanceof String) {
                jsonArray.add((String) element);
            } else if (element instanceof Character) {
                jsonArray.add((Character) element);
            } else if (element instanceof Number) {
                jsonArray.add((Number) element);
            } else if (element instanceof Boolean) {
                jsonArray.add((Boolean) element);
            } else if (element instanceof Collection) {
                jsonArray.add(toJson((Collection<?>) element));
            } else if (element instanceof Document) {
                jsonArray.add(toJson((Document) element));
            }
        }
        return jsonArray;
    }

}
