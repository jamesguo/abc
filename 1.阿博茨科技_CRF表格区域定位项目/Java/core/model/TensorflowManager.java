package com.abcft.pdfextract.core.model;

import com.abcft.pdfextract.config.PropertiesConfig;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.tensorflow.SavedModelBundle;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class TensorflowManager {

    private static Logger logger = LogManager.getLogger();
    public static final TensorflowManager INSTANCE = new TensorflowManager();
    public static final String FONT_OCR = "font-ocr";
    public static final String PARAGRAPH = "paragraph";
    public static final String LINE_CRF = "line-crf";
    public static final String LINE_CRF_TABLE = "line-crf-table";//与段落的CRF模型进行区分
    public static final String CELL_MERGE = "cell-merge";
    public static final String TABLE_CLASSIFY = "table_classify";//位图表格分类

    private final File modelDir;
    private final Map<String, SavedModelBundle> savedModelBundleMap;
    private final Map<String, Vocabulary> vocabularyMap;
    private final TensorflowConfig config;

    private TensorflowManager() {
        savedModelBundleMap = new HashMap<>();
        vocabularyMap = new HashMap<>();
        config = new TensorflowConfig();
        modelDir = config.getModelDirectory();
    }


    public boolean isModelAvailable(String name) {
        if (modelDir == null || !config.isEnabled()) {
            return false;
        }
        File exportDir = new File(modelDir, name);
        return exportDir.exists();
    }

    public SavedModelBundle getSavedModelBundle(String name) {
        synchronized (savedModelBundleMap) {
            SavedModelBundle bundle = savedModelBundleMap.get(name);
            if (bundle != null) {
                return bundle;
            }
            File exportDir = new File(modelDir, name);
            if (!exportDir.exists()) {
                return null;
            }
            bundle = SavedModelBundle.load(exportDir.getPath(), "serve");
            savedModelBundleMap.put(name, bundle);
            return bundle;
        }
    }

    public Vocabulary getVocabulary(String name) {
        synchronized (vocabularyMap) {
            Vocabulary vocabulary = vocabularyMap.get(name);
            if (vocabulary != null) {
                return vocabulary;
            }
            File vocabFile = Paths.get(modelDir.getPath(), name, "assets", "vocab.txt").toFile();
            if (!vocabFile.exists()) {
                return null;
            }
            vocabulary = new Vocabulary(vocabFile);
            vocabularyMap.put(name, vocabulary);
            return vocabulary;
        }
    }

    public static class Vocabulary {
        private Map<String, Integer> strToIdMap;
        private Map<Integer, String> idToStrMap;

        Vocabulary(File file) {
            strToIdMap = new HashMap<>();
            idToStrMap = new HashMap<>();
            load(file);
        }

        public void load(File file) {
            try {
                InputStream inputStream = new FileInputStream(file);
                List<String> lines = IOUtils.readLines(inputStream, "utf-8");
                int id = 0;
                for (String line : lines) {
                    if (!strToIdMap.containsKey(line.trim())) {
                        idToStrMap.put(id, line.trim());
                        strToIdMap.put(line.trim(), id++);
                    } else {
                        logger.warn("Duplicate word: {} in vocabulary file: {}", line, file.getPath());
                    }
                }
            } catch (Exception e) {
                logger.warn("Load vocabulary file: {} failed", file.getPath());
            }
        }

        public String getWord(int id) {
            return idToStrMap.get(id);
        }

        public int getId(String word) {
            return strToIdMap.get(word);
        }

    }


    public static final class TensorflowConfig extends PropertiesConfig {

        private final boolean enabled;
        private final File modelDir;

        public TensorflowConfig() {
            super("abcft.tensorflow", "tensorflow.properties");
            Properties props = getProperties();
            this.enabled = BooleanUtils.toBoolean(props.getProperty("tensorflow.enabled", "true"));
            this.modelDir = new File(props.getProperty("tensorflow.model_dir", "/home/jhqiu/git/pdfextract/tf-models"));
        }

        public boolean isEnabled() {
            return enabled;
        }

        public File getModelDirectory() {
            return modelDir;
        }
    }
}
