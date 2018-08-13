package com.abcft.pdfextract.core.office;

import com.aspose.slides.Presentation;
import com.aspose.words.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BiFunction;

public class OfficeConverter {
    private static final Logger logger = LogManager.getLogger(OfficeConverter.class);

    private static void setLicense(byte[] data,Class claz) {
        try {
            Method setLicense = claz.getDeclaredMethod("setLicense", InputStream.class);
            Object o = claz.newInstance();
            setLicense.invoke(o, new ByteArrayInputStream(data));
            Method isLicensed = claz.getDeclaredMethod("isLicensed");
            isLicensed.invoke(o);
        } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException | InstantiationException e) {
            e.printStackTrace();
        }
    }

    static {
        ClassLoader classLoader = OfficeConverter.class.getClassLoader();
        InputStream in = classLoader.getResourceAsStream("aspose.words.license.xml");
        byte[] bytes = null;
        try {
            bytes = IOUtils.toByteArray(in);
            setLicense(bytes,com.aspose.words.License.class);
            setLicense(bytes,com.aspose.slides.License.class);
            logger.info("Aspose.total set license successful");
        } catch (IOException e) {

        }
    }

    /**
     * convert ".doc" word or ".docx" word  to ".pdf" pdf
     * @param inputStream
     * @param outputStream
     * @return true if converted or **NO need** to convert
     */
    static boolean convertWord2PDF(InputStream inputStream, OutputStream outputStream){
       try {
            Document document = new Document(inputStream);
            document.removeMacros();
            document.save(outputStream,SaveFormat.PDF);
            logger.info("word convert done");
            return true;
        } catch (Exception e) {
           logger.error("word convert ERROR,{}", e);
       }
       return false;
    }

    /**
     *
     * @param inputStream
     * @param file
     * @return
     */
    static boolean convertWord2PDF(InputStream inputStream, File file) {
        boolean flag = false;
        try {
            flag = convertWord2PDF(inputStream, new FileOutputStream(file));
            if(!flag){
                FileUtils.deleteQuietly(file);
            }
        } catch (Exception e) {
            logger.info("file-->stream,{}",e);
            FileUtils.deleteQuietly(file);
        }
        return flag;
    }


    /**
     * convert ".ppt" ppt to ".pptx" ppt  to  ".pdf"  pdf
     * @param inputStream
     * @param outputStream
     * @return true if converted or **NO need** to convert
     */
    static boolean convertPPT2PDF(InputStream inputStream, OutputStream outputStream) {
        try {
            Presentation slides = new Presentation(inputStream);
            slides.save(outputStream, com.aspose.slides.SaveFormat.Pdf);
            logger.info("ppt convert done");
            return true;
        } catch (Exception e) {
            logger.error("ppt convert ERROR ,{}", e);
        }
        return false;
    }

    /**
     *
     * @param inputStream
     * @param file
     * @return
     */
    static boolean convertPPT2PDF(InputStream inputStream, File file) {
        boolean flag = false;
        try {
            flag  = convertPPT2PDF(inputStream, new FileOutputStream(file));
            if(!flag){
                FileUtils.deleteQuietly(file);
            }
        } catch (Exception e) {
            logger.info("file-->stream,{}",e);
            FileUtils.deleteQuietly(file);
        }
        return flag;
    }

    /**
     * convert ".doc" word to ".docx" word
     * @param inputStream
     * @param outputStream
     * @return true if converted or **NO need** to convert
     */
  static boolean convertDocToDocx(InputStream inputStream, OutputStream outputStream) {
        try(ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            IOUtils.copy(inputStream, baos);
            inputStream = new ByteArrayInputStream(baos.toByteArray());
            FileFormatInfo info = FileFormatUtil.detectFileFormat(inputStream);
            if (info.getLoadFormat() == LoadFormat.DOCX) {
                logger.warn("Word NO need to convert");
                outputStream.write(baos.toByteArray());
                outputStream.flush();
                return true;
            }
            inputStream = new ByteArrayInputStream(baos.toByteArray());
            Document document = new Document(inputStream);
            document.removeMacros();
            document.save(outputStream, LoadFormat.DOCX);
            logger.info("Word convert done");
            return true;
        } catch (Exception e) {
            logger.error("Word convert ERROR", e);
            e.printStackTrace();
        }
        return false;
    }

    /**
     * 转化接口
     * @param inputStream
     * @param outputStream
     * @param convert
     * @return
     */
   public static boolean convert(InputStream inputStream, OutputStream outputStream, BiFunction<InputStream,OutputStream,Boolean> convert) {
         return convert.apply(inputStream,outputStream);
    }

    /**
     * 转化接口
     * @param inputStream
     * @param file
     * @param convert
     * @return
     */
    public static boolean convert(InputStream inputStream, File file, BiFunction<InputStream,File,Boolean> convert) {
        return convert.apply(inputStream,file);
    }


    public static void main(String[] args) {
        try {
            String filename = args[0];
            FileInputStream fileInputStream = new FileInputStream(filename);
            FileOutputStream fileOutputStream = new FileOutputStream(filename.replace("pptx", ".pdf"));
            boolean converted = convert(fileInputStream, fileOutputStream,OfficeConverter::convertPPT2PDF);
            if (converted) {
                System.out.println("converted!");
                fileOutputStream.flush();
                fileOutputStream.close();
            } else {
                System.out.println("NOT converted!");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
