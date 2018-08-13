package com.abcft.pdfextract.core.chart;

import com.abcft.pdfextract.spi.OCRClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import okhttp3.*;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Created by dhu on 2017/7/25.
 */
public class OCRHttpClient implements OCRClient {

    private static Logger logger = LogManager.getLogger();

    // 设置相关时间
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final MediaType MEDIA_TYPE_PNG = MediaType.parse("image/png");
    private final String url;

    public OCRHttpClient() {
        this("http://10.12.8.8:9000/batch");
    }

    public OCRHttpClient(String url) {
        this.url = url;
    }

    @Override
    public List<String> ocr(List<BufferedImage> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }

        MultipartBody.Builder builder = createBodyBuilder();
        // 遍历加入BufferImage对象
        for (BufferedImage image : images) {
            if (!addFormDataPart(builder, image)) {
                return null;
            }
        }
        // 封装成 RequestBody对象
        RequestBody requestBody = builder.build();

        // 建立Request对象
        Request request = new Request.Builder().url(url).post(requestBody).build();

        // 远程访问服务
        return getStringsByHttpRequest(request);
    }

    protected MultipartBody.Builder createBodyBuilder() {
        return new MultipartBody.Builder().setType(MultipartBody.FORM);
    }

    public boolean addFormDataPart(MultipartBody.Builder builder, BufferedImage image) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "PNG", stream);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        IOUtils.closeQuietly(stream);
        byte[] data = stream.toByteArray();
        builder.addFormDataPart("image[]", "image.png",
                RequestBody.create(MEDIA_TYPE_PNG, data));
        return true;
    }

    public java.util.List<String> getStringsByHttpRequest(Request request) {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful())  {
                return null;
            }

            List<String> texts = null;
            String respText = response.body().string();
            try {
                JsonObject resp = new JsonParser().parse(respText).getAsJsonObject();
                Type listType = new TypeToken<List<String>>() {}.getType();
                texts = new Gson().fromJson(resp.get("texts"), listType);
            } catch (Exception e) {
                logger.warn("ocr response error: \n" + respText);
            }
            return texts;
        } catch (Exception e) {
            //e.printStackTrace();
            logger.warn("ocr request error", e);
        }
        return null;
    }
}

class TesseractHttpClient extends OCRHttpClient {
    public TesseractHttpClient() {
        this("http://10.12.8.8:9000/batchtesseract");
    }

    public TesseractHttpClient(String url) {
        super(url);
    }

    @Override
    protected MultipartBody.Builder createBodyBuilder() {
        MultipartBody.Builder builder = super.createBodyBuilder();
        builder.addFormDataPart("dealimage", "not merge")
                .addFormDataPart("lang", "chi_sim+eng")
                .addFormDataPart("config", "-psm 7 -oem 0");
        return builder;
    }
}
