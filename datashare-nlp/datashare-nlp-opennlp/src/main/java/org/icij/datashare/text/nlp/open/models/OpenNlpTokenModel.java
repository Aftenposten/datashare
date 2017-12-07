package org.icij.datashare.text.nlp.open.models;

import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.util.model.BaseModel;
import org.icij.datashare.text.Language;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import static org.icij.datashare.text.Language.*;
import static org.icij.datashare.text.nlp.NlpStage.TOKEN;


public class OpenNlpTokenModel extends OpenNlpAbstractModel {
    private static volatile OpenNlpTokenModel instance;
    private static final Object mutex = new Object();

    private final Path modelDir;
    private final Map<Language, Path> modelPath;
    private final Map<Language, TokenizerModel> model;

    public static OpenNlpTokenModel getInstance() {
        OpenNlpTokenModel local_instance = instance; // avoid accessing volatile field
        if (local_instance == null) {
            synchronized(OpenNlpTokenModel.mutex) {
                local_instance = instance;
                if (local_instance == null) {
                    instance = new OpenNlpTokenModel();
                }
            }
        }
        return instance;
    }

    private OpenNlpTokenModel() {
        super(TOKEN);
        modelDir = OpenNlpModels.DIRECTORY.apply(TOKEN);
        modelPath = new HashMap<Language, Path>(){{
            put(ENGLISH, modelDir.resolve("en-token.bin"));
            put(SPANISH, modelDir.resolve("en-token.bin"));
            put(FRENCH,  modelDir.resolve("fr-token.bin"));
            put(GERMAN,  modelDir.resolve("de-token.bin"));
        }};
        model = new HashMap<>();
    }

    @Override
    BaseModel getModel(Language language) {
        return model.get(language);
    }

    @Override
    void putModel(Language language, InputStream content) throws IOException {
        model.put(language, new TokenizerModel(content));
    }

    @Override
    String getModelPath(Language language) {
        return modelPath.get(language).toString();
    }

    public void unload(Language language) {
        Lock l = modelLock.get(language);
        l.lock();
        try {
            model.remove(language);
        } finally {
            l.unlock();
        }
    }
}
