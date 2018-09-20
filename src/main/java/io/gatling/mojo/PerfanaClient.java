package io.gatling.mojo;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;
import okhttp3.*;
import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Enumeration;
import java.util.Properties;

import static java.lang.Integer.parseInt;

public class PerfanaClient {

    private Logger logger = new SystemOutLogger();

    private static final MediaType JSON
            = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient client = new OkHttpClient();

    private final String application;
    private final String testType;
    private final String testEnvironment;
    private final String testRunId;
    private final String CIBuildResultsUrl;
    private final String applicationRelease;
    private final String perfanaUrl;
    private final String rampupTimeSeconds;
    private final String plannedDurationInSeconds;
    private final String annotations;
    private final Properties variables;

    public PerfanaClient(String application, String testType, String testEnvironment, String testRunId, String CIBuildResultsUrl, String applicationRelease, String rampupTimeInSeconds, String constantLoadTimeInSeconds, String perfanaUrl, String annotations, Properties variables) {
        this.application = application;
        this.testType = testType;
        this.testEnvironment = testEnvironment;
        this.testRunId = testRunId;
        this.CIBuildResultsUrl = CIBuildResultsUrl;
        this.applicationRelease = applicationRelease;
        this.rampupTimeSeconds = rampupTimeInSeconds;
        this.plannedDurationInSeconds = String.valueOf(parseInt(rampupTimeInSeconds) + parseInt(constantLoadTimeInSeconds));
        this.perfanaUrl = perfanaUrl;
        this.annotations = annotations;
        this.variables = variables;
    }

    public void injectLogger(Logger logger) {
        this.logger = logger;
    }

    public void callPerfana(Boolean completed) {
        String json = perfanaJson(application, testType, testEnvironment, testRunId, CIBuildResultsUrl, applicationRelease, rampupTimeSeconds, plannedDurationInSeconds, annotations, variables, completed);
        logger.debug(String.join(" ", "Call to endpoint:", perfanaUrl, "with json:", json));
        try {
            String result = post(perfanaUrl + "/test", json);
            logger.debug("Result: " + result);
        } catch (IOException e) {
            logger.error("Failed to call perfana: " + e.getMessage());
        }
    }

    private String post(String url, String json) throws IOException {
        RequestBody body = RequestBody.create(JSON, json);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            return responseBody == null ? "null" : responseBody.string();
        }
    }

    private String perfanaJson(String application, String testType, String testEnvironment, String testRunId, String CIBuildResultsUrl, String applicationRelease, String rampupTimeSeconds, String plannedDurationInSeconds, String annotations, Properties variables, Boolean completed) {

        JSONObject perfanaJson = new JSONObject();

        /* If variables parameter exists add them to the json */

        if(variables != null && !variables.isEmpty()) {

            JSONArray variablesArrayJson = new JSONArray();

            Enumeration<?> enumeration = variables.propertyNames();
            while (enumeration.hasMoreElements()) {
                String name = (String) enumeration.nextElement();
                String value = (String) variables.get(name);
                JSONObject variablesJson = new JSONObject();
                variablesJson.put("placeholder", name);
                variablesJson.put("value", value);
                variablesArrayJson.add(variablesJson);
            }

            perfanaJson.put("variables", variablesArrayJson);
        }

        /* If annotations are passed add them to the json */

        if(!"".equals(annotations) && annotations != null ){

            perfanaJson.put("annotations", annotations);

        }

        perfanaJson.put("testRunId", testRunId);
        perfanaJson.put("testType", testType);
        perfanaJson.put("testEnvironment", testEnvironment);
        perfanaJson.put("application", application);
        perfanaJson.put("applicationRelease", applicationRelease);
        perfanaJson.put("CIBuildResultsUrl", CIBuildResultsUrl);
        perfanaJson.put("rampUp", rampupTimeSeconds);
        perfanaJson.put("duration", plannedDurationInSeconds);
        perfanaJson.put("completed", completed);

        return perfanaJson.toJSONString();


    }

    /**
     * Call asserts for this test run.
     * @return json string such as {"meetsRequirement":true,"benchmarkResultPreviousOK":true,"benchmarkResultFixedOK":true}
     * @throws IOException when call fails
     */
    public String callCheckAsserts() throws IOException, MojoExecutionException {
        // example: https://targets-io.com/benchmarks/DASHBOARD/NIGHTLY/TEST-RUN-831
        String url = String.join("/", perfanaUrl, "get-benchmark-results", URLEncoder.encode(application, "UTF-8").replaceAll("\\+", "%20"), URLEncoder.encode(testRunId, "UTF-8").replaceAll("\\+", "%20") );
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        int retries = 0;
        final int MAX_RETRIES = 12;
        final long sleepInMillis = 10000;
        String assertions = null;

        while (retries <= MAX_RETRIES) {
            try (Response response = client.newCall(request).execute()) {

                ResponseBody responseBody = response.body();
                if (response.code() == 200) {
                    assertions = responseBody == null ? "null" : responseBody.string();
                    break;
                } else {
                    String message = responseBody == null ? response.message() : responseBody.string();
                    logger.warn("failed to retrieve assertions for url [" + url + "] code [" + response.code() + "] retry [" + retries + "/" + MAX_RETRIES + "] " + message);
                }
                try {
                    Thread.sleep(sleepInMillis);
                } catch (InterruptedException e) {
                    // ignore
                }
                retries = retries + 1;
            }
            if (retries == MAX_RETRIES) {
                throw new MojoExecutionException("Unable to retrieve assertions for url [" + url + "]");
            }
        }
        return assertions;
    }



    public static class KeepAliveRunner implements Runnable {

        private final PerfanaClient client;

        public KeepAliveRunner(PerfanaClient client) {
            this.client = client;
        }

        @Override
        public void run() {
            client.callPerfana(false);
        }
    }

    public interface Logger {
        void info(String message);
        void warn(String message);
        void error(String message);
        void debug(String message);
    }

    public static class SystemOutLogger implements Logger {
        public void info(String message) {
            System.out.println("INFO:  " + message);
        }

        public void warn(String message) {
            System.out.println("WARN:  " + message);
        }

        public void error(String message) {
            System.out.println("ERROR: " + message);
        }

        public void debug(String message) {
            System.out.println("DEBUG: " + message);
        }
    }

}
