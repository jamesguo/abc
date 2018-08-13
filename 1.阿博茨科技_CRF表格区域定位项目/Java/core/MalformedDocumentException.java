package com.abcft.pdfextract.core;

import java.io.IOException;

/**
 * Signals that a document is malformed.
 */
public class MalformedDocumentException extends IOException {

    public MalformedDocumentException(String message) {
        super(message);
    }

    public MalformedDocumentException(Throwable cause) {
        super(cause);
    }


    public MalformedDocumentException(String message, Throwable cause) {
        super(message, cause);
    }

}
