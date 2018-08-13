package com.abcft.pdfextract.core.util;

import com.mongodb.client.model.Filters;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.Document;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.io.Serializable;
import java.math.BigInteger;
import java.text.ParseException;
import java.util.Date;
import java.util.function.Function;


/**
 * Represents a file id.
 *
 * Created by chzhong on 17-5-24.
 */
public abstract class FileId implements Comparable<FileId>, Serializable {

    private static final String FIELD_ID = "_id";

    public static StringId createIdForItem(Object documentId, int pageIndex, String itemName) {
        String stringId = String.format("%s_%d_%s", documentId.toString(), pageIndex, itemName);
        return new StringId(stringId);
    }

    public static StringId replaceItemId(FileId oldItemId, FileId fileId) {
        StringBuilder oldId = new StringBuilder(oldItemId.toString());
        int end = oldId.indexOf("_");
        if (end > 0) {
            oldId.replace(0, end, fileId.toString());
        } else {
            oldId.replace(0, oldId.length(), fileId.toString());
        }
        return new StringId(oldId.toString());
    }


    /**
     * Creates a filter that matches a documents where the {@value #FIELD_ID} of the document equals specified value.
     *
     * @param value the value.
     * @return the filter.
     */
    public static Bson createFilter(Object value) {
        if (value instanceof FileId) {
            value = ((FileId)value).value;
        }
        return Filters.eq(FIELD_ID, value);
    }

    /**
     * Creates a document with the {@value #FIELD_ID} as specified value.
     *
     * @param id the id.
     * @return the document.
     */
    public static Document createDocument(Object id) {
        if (id instanceof FileId) {
            id = ((FileId)id).value;
        }
        return new Document(FIELD_ID, id);
    }

    /**
     * Converts an object to {@linkplain FileId}.
     *
     * @param id the file id of original type.
     * @return a wrapped {@linkplain FileId}.
     */
    public static FileId fromObject(Object id) {
        if (id instanceof FileId) {
            return (FileId)id;
        } else if (id instanceof Long) {
            return new NumberId((Long)id);
        } else if (id instanceof Integer) {
            return new NumberId(((Integer)id).longValue());
        } else if (id instanceof ObjectId) {
            return new MongoObjectId((ObjectId)id);
        } else if (id instanceof String) {
            return new StringId((String)id);
        } else if (id instanceof Date) {
            return new DateId((Date)id);
        } else if (id != null) {
            throw new IllegalArgumentException("Unsupported id type " + id + " (type " + id.getClass() + ")");
        } else {
            return null;
        }
    }

    /**
     * Parse {@linkplain FileId} from string.
     *
     * @param id the string representation of {@linkplain FileId}.
     * @return a instance of {@linkplain FileId} of the given string.
     */
    public static FileId parse(String id) {
        return parse(id, StringId::new);
    }


    /**
     * Parse {@linkplain FileId} from string.
     *
     * @param id the string representation of {@linkplain FileId}.
     * @param parser extract parser to parse the string.
     * @return a instance of {@linkplain FileId} of the given string.
     */
    public static FileId parse(String id, Function<String, FileId> parser) {
        if (ObjectId.isValid(id)) {
            return new MongoObjectId(new ObjectId(id));
        } else if (StringUtils.isNumeric(id)) {
            return new NumberId(Long.parseLong(id));
        } else {
            try {
                Date date = DateUtils.parseDate(id,
                        "yyyy-MM-dd HH:mm:ss",
                        "yyyy-MM-dd hh:mm:ss a",
                        "yyyy-MM-dd HH:mm:ss.SSS",
                        "yyyy/MM/dd HH:mm:ss",
                        "yyyy/MM/dd hh:mm:ss a",
                        "yyyy/MM/dd HH:mm:ss.SSS",
                        "MM/dd/yyyy HH:mm:ss",
                        "MM/dd/yyyy hh:mm:ss a",
                        "dd/MM/yyyy HH:mm:ss",
                        "dd/MM/yyyy hh:mm:ss a",
                        "MM dd, yyyy HH:mm:ss",
                        "MM dd, yyyy hh:mm:ss a",
                        "dd MM, yyyy HH:mm:ss",
                        "dd MM, yyyy hh:mm:ss a",
                        "yyyy-MM-dd'T'HH:mm:ss'Z'",
                        "yyyy-MM-dd'T'HH:mm:ssz",
                        "yyyy-MM-dd'T'HH:mm:ssZ",
                        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        "yyyy-MM-dd'T'HH:mm:ss.SSSz",
                        "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
                );
                return new DateId(date);
            } catch (ParseException e) {
                if (parser != null) {
                    return parser.apply(id);
                } else {
                    throw new IllegalArgumentException("Unsupported file id " + id);
                }
            }
        }
    }

    /**
     * Read the {@linkplain FileId} from a {@linkplain Document}.
     * @param document the document with {@value #FIELD_ID} field.
     * @return a instance of {@linkplain FileId} of specified document.
     */
    public static FileId fromDocument(Document document) {
        return fromObject(document.get(FIELD_ID));
    }

    /**
     * Read the {@linkplain FileId} from a {@linkplain Document}.
     * @param document the document with {@code key} field.
     * @param key the name of the id field.
     * @return a instance of {@linkplain FileId} of specified document.
     */
    public static FileId fromDocument(Document document, String key) {
        return fromObject(document.get(key));
    }

    protected FileId(Comparable value) {
        if (null == value)
            throw new NullPointerException("value may not be null.");
        this.value = value;
    }

    private final Comparable value;

    /**
     * Returns the original id.
     *
     * @return the value of the original id.
     */
    public Comparable value() {
        return value;
    }

    /**
     * Return the next id, aka: {@code value + 1}.
     *
     * @return  the next id.
     */
    public abstract FileId next();

    /**
     * Return the previous id, aka: {@code value - 1}.
     *
     * @return  the previous id.
     */
    public abstract FileId prev();

    /**
     * Creates a {@linkplain Document} of current id.
     *
     * @return a {@linkplain Document} with { {@value #FIELD_ID}: {@linkplain #value()} }.
     */
    public Document toDocument() {
        return new Document(FIELD_ID, value);
    }

    /**
     * Creates a filter that matches a documents where the {@value #FIELD_ID} of the document equals {@linkplain #value()}.
     *
     * @return the filter.
     */
    public Bson toFilter() {
        return createFilter(value);
    }

    /**
     * Creates a filter that matches a documents where the {@code key} of the document equals {@linkplain #value()}.
     *
     * @param key the key to match.
     * @return the filter.
     */
    public Bson toFilter(String key) {
        return Filters.eq(key, value);
    }

    /**
     * Append {@linkplain #value()} to {@value #FIELD_ID} field to specified {@linkplain Document}.
     *
     * @param document the document to append to.
     * @return the document with appended { {@value #FIELD_ID} : {@linkplain #value()} }.
     */
    public Document appendToDocument(Document document) {
        return appendToDocument(document, FIELD_ID);
    }

    /**
     * Append {@linkplain #value()} to {@code key} to specified {@linkplain Document}.
     *
     * @param document the document to append to.
     * @param key the key to assign with.
     *
     * @return the document with appended { {@code key} : {@linkplain #value()} }.
     */
    public Document appendToDocument(Document document, String key) {
        return document.append(key, value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FileId fileId = (FileId) o;

        return value.equals(fileId.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString();
    }

    @Override
    public int compareTo(FileId o) {
        if (this == o) return 0;
        if (o == null)
            throw new NullPointerException("o is null.");
        if (getClass() != o.getClass())
            throw new ClassCastException("FileId Type differs: " + this.getClass() + " vs " + o.getClass());
        if (this.value.getClass() != o.value.getClass())
            throw new ClassCastException("FileId value type differs: " + this.value.getClass() + " vs " + o.value.getClass());
        //noinspection unchecked
        return this.value.compareTo(o.value);
    }


    public static final Codec<NumberId> NUMBER_ID_CODEC =  new Codec<NumberId>() {

        @Override
        public NumberId decode(BsonReader reader, DecoderContext decoderContext) {
            return new NumberId(reader.readInt64());
        }

        @Override
        public void encode(BsonWriter writer, NumberId value, EncoderContext encoderContext) {
            writer.writeInt64(value.value());
        }

        @Override
        public Class<NumberId> getEncoderClass() {
            return NumberId.class;
        }
    };

    public static final Codec<StringId> STRING_ID_CODEC =  new Codec<StringId>() {

        @Override
        public StringId decode(BsonReader reader, DecoderContext decoderContext) {
            return new StringId(reader.readString());
        }

        @Override
        public void encode(BsonWriter writer, StringId value, EncoderContext encoderContext) {
            writer.writeString(value.value());
        }

        @Override
        public Class<StringId> getEncoderClass() {
            return StringId.class;
        }
    };

    public static final Codec<MongoObjectId> OBJECT_ID_CODEC =  new Codec<MongoObjectId>() {

        @Override
        public MongoObjectId decode(BsonReader reader, DecoderContext decoderContext) {
            return new MongoObjectId(reader.readObjectId());
        }

        @Override
        public void encode(BsonWriter writer, MongoObjectId value, EncoderContext encoderContext) {
            writer.writeObjectId(value.value());
        }

        @Override
        public Class<MongoObjectId> getEncoderClass() {
            return MongoObjectId.class;
        }
    };


    public static final Codec<DateId> DATE_ID_CODEC =  new Codec<DateId>() {

        @Override
        public DateId decode(BsonReader reader, DecoderContext decoderContext) {
            return new DateId(new Date(reader.readDateTime()));
        }

        @Override
        public void encode(BsonWriter writer, DateId value, EncoderContext encoderContext) {
            writer.writeDateTime(value.value().getTime());
        }

        @Override
        public Class<DateId> getEncoderClass() {
            return DateId.class;
        }
    };


    /**
     * String-form of {@linkplain FileId}.
     * <p>
     * {@value #FIELD_ID} is type of {@linkplain String}.
     */
    public static final class StringId extends FileId {

        private StringId(String value) {
            super(value);
        }

        @Override
        public String value() {
            return (String) super.value();
        }

        @Override
        public StringId next() {
            String v = value();
            if (StringUtils.isEmpty(v)) {
                return new StringId(String.valueOf('\u0001'));
            }
            BigInteger intValue = new BigInteger(v.getBytes());
            intValue = intValue.add(BigInteger.ONE);
            return new StringId(new String(intValue.toByteArray()));
        }

        @Override
        public StringId prev() {
            String v = value();
            if (StringUtils.isEmpty(v)) {
                return null;
            }
            BigInteger intValue = new BigInteger(v.getBytes());
            intValue = intValue.subtract(BigInteger.ONE);
            return new StringId(new String(intValue.toByteArray()));
        }
    }

    /**
     * Number-form of {@linkplain FileId}.
     * <p>
     * {@value #FIELD_ID} is type of {@code long} or {@code int}.
     */
    public static final class NumberId extends FileId {

        private NumberId(long value) {
            super(value);
        }

        @Override
        public Long value() {
            return (Long) super.value();
        }

        @Override
        public NumberId next() {
            return new NumberId(value() + 1L);
        }

        @Override
        public NumberId prev() {
            return new NumberId(value() - 1L);
        }
    }

    /**
     * ObjectId-form of {@linkplain FileId}.
     * <p>
     * {@value #FIELD_ID} is type of {@linkplain ObjectId}.
     */
    public static final class MongoObjectId extends FileId {

        private MongoObjectId(ObjectId value) {
            super(value);
        }

        @Override
        public ObjectId value() {
            return (ObjectId) super.value();
        }

        @Override
        public MongoObjectId next() {
            ObjectId v = value();
            BigInteger intValue = new BigInteger(v.toByteArray());
            intValue = intValue.add(BigInteger.ONE);
            return new MongoObjectId(new ObjectId(intValue.toByteArray()));
        }

        @Override
        public MongoObjectId prev() {
            ObjectId v = value();
            BigInteger intValue = new BigInteger(v.toByteArray());
            intValue = intValue.subtract(BigInteger.ONE);
            return new MongoObjectId(new ObjectId(intValue.toByteArray()));
        }
    }

    /**
     * Date-form of {@linkplain FileId}.
     * <p>
     * {@value #FIELD_ID} is type of {@linkplain Date}.
     */
    public static final class DateId extends FileId {

        private DateId(Date value) {
            super(value);
        }

        @Override
        public Date value() {
            return (Date) super.value();
        }

        @Override
        public DateId next() {
            return new DateId(new Date(value().getTime() + 1L));
        }

        @Override
        public DateId prev() {
            return new DateId(new Date(value().getTime() - 1L));
        }
    }
}
