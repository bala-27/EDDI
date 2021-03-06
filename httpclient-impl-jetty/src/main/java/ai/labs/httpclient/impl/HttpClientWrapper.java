package ai.labs.httpclient.impl;

import ai.labs.httpclient.ICompleteListener;
import ai.labs.httpclient.IHttpClient;
import ai.labs.httpclient.IRequest;
import ai.labs.httpclient.IResponse;
import com.sun.org.apache.xml.internal.security.utils.Base64;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.*;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;

import javax.inject.Inject;
import java.net.CookieStore;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Slf4j
public class HttpClientWrapper implements IHttpClient {
    private final HttpClient httpClient;

    @Inject
    public HttpClientWrapper(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public CookieStore getCookieStore() {
        return httpClient.getCookieStore();
    }

    @Override
    public IRequest newRequest(URI uri) {
        return newRequest(uri, Method.GET);
    }

    @Override
    public IRequest newRequest(URI uri, Method method) {
        Request request = httpClient.newRequest(uri).method(method.name());
        return new RequestWrapper(uri, request);
    }

    public class RequestWrapper implements IRequest {
        private final URI uri;
        private Request request;
        private int maxLength = 2 * 1024 * 1024;

        public RequestWrapper(URI uri, Request request) {
            this.uri = uri;
            this.request = request;
        }

        @Override
        public IRequest setBasicAuthentication(String username, String password, String realm, boolean preemptive) {
            if (preemptive) {
                request.getHeaders().add("Authorization", "Basic " + Base64.encode(String.valueOf(username + ":" + password).getBytes()));
            } else {
                AuthenticationStore auth = httpClient.getAuthenticationStore();
                auth.addAuthentication(new BasicAuthentication(uri, realm, username, password));
            }

            return this;
        }

        public IRequest setHttpHeader(String headerName, String value) {
            request.getHeaders().add(headerName, value);
            return this;
        }

        @Override
        public IRequest setQueryParam(String key, String value) {
            request.param(key, value);
            return this;
        }

        @Override
        public IRequest setUserAgent(String userAgent) {
            request.agent(userAgent);
            return this;
        }

        @Override
        public IRequest setBodyEntity(String content, String encoding, String contentType) {
            request.content(new StringContentProvider(content, encoding), contentType);
            return this;
        }

        @Override
        public IRequest setMaxResponseSize(int maxLength) {
            this.maxLength = maxLength;

            return this;
        }

        @Override
        public IRequest setTimeout(long timeout, TimeUnit timeUnit) {
            request.timeout(timeout, timeUnit);

            return this;
        }

        @Override
        public IResponse send() throws HttpRequestException {
            final FutureResponseListener listener = new FutureResponseListener(request, maxLength);
            request.send(listener);
            try {
                final ContentResponse response = listener.get();
                return new IResponse() {
                    @Override
                    public String getContentAsString() {
                        return listener.getContentAsString();
                    }

                    @Override
                    public int getHttpCode() {
                        return response.getStatus();
                    }

                    @Override
                    public String getHttpCodeMessage() {
                        return response.getReason();
                    }

                    @Override
                    public Map<String, String> getHttpHeader() {
                        return convertHeaderToMap(response.getHeaders());
                    }
                };
            } catch (InterruptedException | ExecutionException e) {
                listener.cancel(true);
                throw new HttpRequestException(e.getLocalizedMessage(), e);
            }
        }

        @Override
        public void send(final ICompleteListener completeListener) throws HttpRequestException {
            final BufferingResponseListener responseListener = new BufferingResponseListener(maxLength) {
                @Override
                public void onComplete(final Result result) {
                    final Response response = result.getResponse();
                    final String content = getContentAsString();

                    try {
                        completeListener.onComplete(new IResponse() {
                            @Override
                            public String getContentAsString() {
                                return content;
                            }

                            @Override
                            public int getHttpCode() {
                                return response.getStatus();
                            }

                            @Override
                            public String getHttpCodeMessage() {
                                return response.getReason();
                            }

                            @Override
                            public Map<String, String> getHttpHeader() {
                                return convertHeaderToMap(response.getHeaders());
                            }
                        });
                    } catch (IResponse.HttpResponseException e) {
                        log.error(e.getLocalizedMessage(), e);
                    }
                }
            };

            request.send(responseListener);
        }
    }

    private static Map<String, String> convertHeaderToMap(HttpFields headers) {
        Map<String, String> httpHeader = new HashMap<>();
        for (HttpField header : headers) {
            httpHeader.put(header.getName(), header.getValue());
        }
        return httpHeader;
    }
}
