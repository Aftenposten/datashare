package org.icij.datashare.utils;

import net.codestory.http.payload.Payload;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static net.codestory.http.payload.Payload.ok;

public class PayloadFormatter {

    static public Payload error(String message, int status) {
        Map<String, String> responseBody = Collections.singletonMap("error", message);
        return json(responseBody).withCode(status);
    }

    static public Payload error(RuntimeException error, int status) {
        String message = error.getMessage();
        return error(message, status);
    }

    static public Payload json(String responseBody) {
        return new Payload("application/json", responseBody);
    }

    static public Payload json(Object content) {
        return new Payload("application/json", content);
    }

    static public Payload allowMethods(String... methodOrMethods) {
        List<String> methods = Arrays.stream(methodOrMethods)
                .flatMap(s -> Arrays.stream(s.split(",")))
                .map(String::trim)
                .collect(Collectors.toList());
        return ok().withAllowMethods(methods.toArray(new String[0]));
    }
}
