/*
 * Overchan Android (Meta Imageboard Client)
 * Copyright (C) 2014-2015  miku-nyan <https://github.com/miku-nyan>
 *     
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package nya.miku.wishmaster.http.streamer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;

import javax.net.ssl.SSLException;

import nya.miku.wishmaster.api.interfaces.CancellableTask;
import nya.miku.wishmaster.api.interfaces.ProgressListener;
import nya.miku.wishmaster.common.IOUtils;
import nya.miku.wishmaster.common.Logger;
import nya.miku.wishmaster.http.client.ExtendedHttpClient;
import nya.miku.wishmaster.lib.org_json.JSONArray;
import nya.miku.wishmaster.lib.org_json.JSONException;
import nya.miku.wishmaster.lib.org_json.JSONObject;
import nya.miku.wishmaster.lib.org_json.JSONTokener;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.message.BasicHeader;

/**
 * Выполнение HTTP запросов.
 * @author miku-nyan
 *
 */

/* Google пометила все классы и интерфейсы пакета org.apache.http как "deprecated" в API 22 (Android 5.1)
 * На самом деле используется актуальная версия apache-hc httpclient 4.3.5.1-android
 * Подробности: https://issues.apache.org/jira/browse/HTTPCLIENT-1632 */
@SuppressWarnings("deprecation")

public class HttpStreamer {
    private static final String TAG = "HttpStreamer";
    
    private HttpStreamer() {}
    
    private static HttpStreamer instance = null;
    
    /**
     * Вызывается в {@link android.app.Application#onCreate()}
     */
    public static void initInstance() {
        if (instance == null) instance = new HttpStreamer();
    }
    
    public static HttpStreamer getInstance() {
        if (instance == null) { //should never happens
            Logger.e(TAG, "HttpStreamer is not initialized");
            initInstance();
        }
        return instance;
    }
    
    /** таблица с временами If-Modified-Since */
    private final HashMap<String, String> ifModifiedMap = new HashMap<String, String>();
    
    /**
     * Удалить url из таблицы времён изменения If-Modified-Since
     * @param url
     * @return значение удалённого элемента, или NULL, если такой элемент не был найден 
     */
    public String removeFromModifiedMap(String url) {
        String value = null;
        synchronized (ifModifiedMap) {
            value = ifModifiedMap.remove(url);
        }
        return value;
    }
    
    /**
     * HTTP запрос по адресу. После завершения работы с запросом, необходимо выполнить метод release() модели ответа!
     * Если по данному адресу предполагаются запросы с заголовком If-Modified-Since, в случае ошибки при дальнейшем чтении из потока
     * необходимо очистить соответствующию запись в таблице времён Modified ({@link #removeFromModifiedMap(String)})!
     * @param url адрес страницы
     * @param requestModel модель запроса (может принимать null, по умолчанию GET без проверки If-Modified)
     * @param httpClient HTTP клиент, исполняющий запрос
     * @param listener интерфейс отслеживания прогресса (может принимать null)
     * @param task задача, отмена которой прервёт поток (может принимать null)
     * @return модель содержит поток и другие данные HTTP ответа
     * @throws HttpRequestException исключение, если не удалось выполнить запрос
     */
    public HttpResponseModel getFromUrl(String url, HttpRequestModel requestModel, HttpClient httpClient, ProgressListener listener,
            CancellableTask task) throws HttpRequestException {
        if (requestModel == null) requestModel = HttpRequestModel.builder().setGET().build();
        
        //подготавливаем Request
        HttpUriRequest request = null;
        try {
            RequestConfig requestConfigBuilder = ExtendedHttpClient.
                    getDefaultRequestConfigBuilder(requestModel.timeoutValue).
                    setRedirectsEnabled(!requestModel.noRedirect).
                    build();
            
            RequestBuilder requestBuilder = null;
            switch (requestModel.method) {
                case HttpRequestModel.METHOD_GET:
                    requestBuilder = RequestBuilder.get().setUri(url);
                    break;
                case HttpRequestModel.METHOD_POST:
                    requestBuilder = RequestBuilder.post().setUri(url).setEntity(requestModel.postEntity);
                    break;
                default:
                    throw new IllegalArgumentException("Incorrect type of HTTP Request");
            }
            if (requestModel.customHeaders != null) {
                for (Header header : requestModel.customHeaders) {
                    requestBuilder.addHeader(header);
                }
            }
            if (requestModel.checkIfModified && requestModel.method == HttpRequestModel.METHOD_GET) {
                synchronized (ifModifiedMap) {
                    if (ifModifiedMap.containsKey(url)) {
                        requestBuilder.addHeader(new BasicHeader(HttpHeaders.IF_MODIFIED_SINCE, ifModifiedMap.get(url)));
                    }
                }
            }
            request = requestBuilder.setConfig(requestConfigBuilder).build();
        } catch (Exception e) {
            Logger.e(TAG, e);
            HttpResponseModel.release(request, null);
            throw new IllegalArgumentException(e);
        }
        //делаем запрос
        HttpResponseModel responseModel = new HttpResponseModel();
        HttpResponse response = null;
        try {
            IOException responseException = null;
            for (int i=0; i<5; ++i) {
                try {
                    if (task != null && task.isCancelled()) throw new InterruptedException();
                    response = httpClient.execute(request);
                    responseException = null;
                    break;
                } catch (IOException e) {
                    Logger.e(TAG, e);
                    responseException = e;
                    if (e.getMessage() == null) break;
                    if (e.getMessage().indexOf("Connection reset by peer") != -1 ||
                            e.getMessage().indexOf("I/O error during system call, Broken pipe") != -1) {
                        continue;
                    } else {
                        break;
                    }
                }
            }
            if (responseException != null) {
                throw responseException;
            }
            if (task != null && task.isCancelled()) throw new InterruptedException();
            //обработка кода состояния HTTP
            StatusLine status = response.getStatusLine();
            responseModel.statusCode = status.getStatusCode();
            responseModel.statusReason = status.getReasonPhrase();
            //обрабока полученных заголовков (headers)
            String lastModifiedValue = null;
            if (responseModel.statusCode == 200) {
                Header header = response.getFirstHeader(HttpHeaders.LAST_MODIFIED);
                if (header != null) lastModifiedValue = header.getValue();
            }
            Header header = response.getFirstHeader(HttpHeaders.LOCATION);
            if (header != null) responseModel.locationHeader = header.getValue();
            responseModel.headers = response.getAllHeaders();
            //поток
            HttpEntity responseEntity = response.getEntity();
            if (responseEntity != null) {
                responseModel.contentLength = responseEntity.getContentLength();
                if (listener != null) listener.setMaxValue(responseModel.contentLength);
                InputStream stream = responseEntity.getContent();
                responseModel.stream = IOUtils.modifyInputStream(stream, listener, task);
            }
            responseModel.request = request;
            responseModel.response = response;
            if (lastModifiedValue != null) {
                synchronized (ifModifiedMap) {
                    ifModifiedMap.put(url, lastModifiedValue);
                }
            }
        } catch (Exception e) {
            Logger.e(TAG, e);
            HttpResponseModel.release(request, response);
            boolean sslException = false;
            if (e instanceof SSLException) sslException = true;
            throw new HttpRequestException(e, sslException);
        }
        
        return responseModel;
    }
    
    
    /**
     * HTTP запрос по адресу, получить массив байт
     * @param url адрес страницы
     * @param requestModel модель запроса (может принимать null, по умолчанию GET без проверки If-Modified)
     * @param httpClient HTTP клиент, исполняющий запрос
     * @param listener интерфейс отслеживания прогресса (может принимать null)
     * @param task задача, отмена которой прервёт поток (может принимать null)
     * @param anyCode загружать содержимое, даже если сервер не вернул HTTP 200, например, html страницы ошибок
     * (данные будут переданы исключению {@link HttpWrongStatusCodeException})
     * @return массив полученных байтов, или NULL, если страница не была изменена (HTTP 304)
     * @throws IOException ошибка ввода/вывода, в т.ч. если поток прерван отменой задачи (подкласс {@link IOUtils.InterruptedStreamException})
     * @throws HttpRequestException если не удалось выполнить запрос, или содержимое отсутствует
     * @throws HttpWrongStatusCodeException если сервер вернул код не 200. При anycode==true будет содержать также HTML содержимое ответа.
     */
    public byte[] getBytesFromUrl(String url, HttpRequestModel requestModel, HttpClient httpClient, ProgressListener listener,
            CancellableTask task, boolean anyCode) throws IOException, HttpRequestException, HttpWrongStatusCodeException {
        HttpResponseModel responseModel = null;
        try {
            responseModel = getFromUrl(url, requestModel, httpClient, listener, task);
            if (responseModel.statusCode == 200) {
                if (responseModel.stream == null) throw new HttpRequestException(new NullPointerException()); 
                ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
                IOUtils.copyStream(responseModel.stream, output);
                return output.toByteArray();
            } else {
                if (responseModel.notModified()) return null;
                if (anyCode) {
                    byte[] html = null;
                    try {
                        ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
                        IOUtils.copyStream(responseModel.stream, output);
                        html = output.toByteArray();
                    } catch (Exception e) {
                        Logger.e(TAG, e);
                    }
                    throw new HttpWrongStatusCodeException(responseModel.statusCode, responseModel.statusCode+" - "+responseModel.statusReason, html);
                } else {
                    throw new HttpWrongStatusCodeException(responseModel.statusCode, responseModel.statusCode+" - "+responseModel.statusReason);
                }
                
            }
        } catch (Exception e) {
            if (responseModel != null) removeFromModifiedMap(url);
            // (responseModel != null) <=> исключение именно во время чтения, а не во время самого запроса (т.е. запись уже устарела в любом случае)
            throw e;
        } finally {
            if (responseModel != null) responseModel.release();
        }
    }
    
    /**
     * HTTP запрос по адресу, получить строку
     * @param url адрес страницы
     * @param requestModel модель запроса (может принимать null, по умолчанию GET без проверки If-Modified)
     * @param httpClient HTTP клиент, исполняющий запрос
     * @param listener интерфейс отслеживания прогресса (может принимать null)
     * @param task задача, отмена которой прервёт поток (может принимать null)
     * @param anyCode загружать содержимое, даже если сервер не вернул HTTP 200, например, html страницы ошибок
     * (данные будут переданы исключению {@link HttpWrongStatusCodeException})
     * @return полученная строка, или NULL, если страница не была изменена (HTTP 304)
     * @throws IOException ошибка ввода/вывода, в т.ч. если поток прерван отменой задачи (подкласс {@link IOUtils.InterruptedStreamException})
     * @throws HttpRequestException если не удалось выполнить запрос, или содержимое отсутствует
     * @throws HttpWrongStatusCodeException если сервер вернул код не 200. При anycode==true будет содержать также HTML содержимое ответа. 
     */
    public String getStringFromUrl(String url, HttpRequestModel requestModel, HttpClient httpClient, ProgressListener listener,
            CancellableTask task, boolean anyCode) throws IOException, HttpRequestException, HttpWrongStatusCodeException {
        byte[] bytes = getBytesFromUrl(url, requestModel, httpClient, listener, task, anyCode);
        if (bytes == null) return null;
        return new String(bytes);
    }
    
    /**
     * HTTP запрос по адресу, получить объект JSON ({@link nya.miku.wishmaster.lib.org_json.JSONObject} актуальная версия org.json)
     * @param url адрес страницы
     * @param requestModel модель запроса (может принимать null, по умолчанию GET без проверки If-Modified)
     * @param httpClient HTTP клиент, исполняющий запрос
     * @param listener интерфейс отслеживания прогресса (может принимать null)
     * @param task задача, отмена которой прервёт поток (может принимать null)
     * @param anyCode загружать содержимое, даже если сервер не вернул HTTP 200, например, html страницы ошибок
     * (данные будут переданы исключению {@link HttpWrongStatusCodeException})
     * @return объект {@link JSONObject}, или NULL, если страница не была изменена (HTTP 304)
     * @throws IOException ошибка ввода/вывода, в т.ч. если поток прерван отменой задачи (подкласс {@link IOUtils.InterruptedStreamException})
     * @throws HttpRequestException если не удалось выполнить запрос, или содержимое отсутствует
     * @throws HttpWrongStatusCodeException если сервер вернул код не 200. При anycode==true будет содержать также HTML содержимое ответа.
     * @throws JSONException в случае ошибки при парсинге JSON
     */
    public JSONObject getJSONObjectFromUrl(String url, HttpRequestModel requestModel, HttpClient httpClient, ProgressListener listener,
            CancellableTask task, boolean anyCode) throws IOException, HttpRequestException, HttpWrongStatusCodeException, JSONException {
        return (JSONObject) getJSONFromUrl(url, requestModel, httpClient, listener, task, anyCode, false);
    }
    
    /**
     * HTTP запрос по адресу, получить массив JSON ({@link nya.miku.wishmaster.lib.org_json.JSONArray} актуальная версия org.json.JSONArray)
     * @param url адрес страницы
     * @param requestModel модель запроса (может принимать null, по умолчанию GET без проверки If-Modified)
     * @param httpClient HTTP клиент, исполняющий запрос
     * @param listener интерфейс отслеживания прогресса (может принимать null)
     * @param task задача, отмена которой прервёт поток (может принимать null)
     * @param anyCode загружать содержимое, даже если сервер не вернул HTTP 200, например, html страницы ошибок
     * (данные будут переданы исключению {@link HttpWrongStatusCodeException})
     * @return объект {@link JSONArray}, или NULL, если страница не была изменена (HTTP 304)
     * @throws IOException ошибка ввода/вывода, в т.ч. если поток прерван отменой задачи (подкласс {@link IOUtils.InterruptedStreamException})
     * @throws HttpRequestException если не удалось выполнить запрос, или содержимое отсутствует
     * @throws HttpWrongStatusCodeException если сервер вернул код не 200. При anycode==true будет содержать также HTML содержимое ответа.
     * @throws JSONException в случае ошибки при парсинге JSON
     */
    public JSONArray getJSONArrayFromUrl(String url, HttpRequestModel requestModel, HttpClient httpClient, ProgressListener listener,
            CancellableTask task, boolean anyCode) throws IOException, HttpRequestException, HttpWrongStatusCodeException, JSONException {
        return (JSONArray) getJSONFromUrl(url, requestModel, httpClient, listener, task, anyCode, true);
    }
    
    private Object getJSONFromUrl(String url, HttpRequestModel requestModel, HttpClient httpClient, ProgressListener listener, CancellableTask task,
            boolean anyCode, boolean isArray) throws IOException, HttpRequestException, HttpWrongStatusCodeException, JSONException {
        HttpResponseModel responseModel = null;
        BufferedReader in = null;
        try {
            responseModel = getFromUrl(url, requestModel, httpClient, listener, task);
            if (responseModel.statusCode == 200) {
                if (responseModel.stream == null) throw new HttpRequestException(new NullPointerException()); 
                in = new BufferedReader(new InputStreamReader(responseModel.stream));
                return isArray ? new JSONArray(new JSONTokener(in)) : new JSONObject(new JSONTokener(in));
            } else {
                if (responseModel.notModified()) return null;
                if (anyCode) {
                    byte[] html = null;
                    try {
                        ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
                        IOUtils.copyStream(responseModel.stream, output);
                        html = output.toByteArray();
                    } catch (Exception e) {
                        Logger.e(TAG, e);
                    }
                    throw new HttpWrongStatusCodeException(responseModel.statusCode, responseModel.statusCode+" - "+responseModel.statusReason, html);
                } else {
                    throw new HttpWrongStatusCodeException(responseModel.statusCode, responseModel.statusCode+" - "+responseModel.statusReason);
                }
                
            }
        } catch (Exception e) {
            if (responseModel != null) removeFromModifiedMap(url);
            throw e;
        } finally {
            IOUtils.closeQuietly(in);
            if (responseModel != null) responseModel.release();
        }
    }
    
    /**
     * Скачать файл (HTTP) по заданному адресу URL, записать в поток out.
     * @param url адрес
     * @param out целевой поток
     * @param requestModel модель запроса (может принимать null, по умолчанию GET без проверки If-Modified)
     * @param httpClient HTTP клиент, исполняющий запрос
     * @param listener интерфейс отслеживания прогресса (может принимать null)
     * @param task задача, отмена которой прервёт поток (может принимать null)
     * @param anyCode загружать содержимое, даже если сервер не вернул HTTP 200, например, html страницы ошибок
     * (данные будут переданы исключению {@link HttpWrongStatusCodeException})
     * @throws IOException ошибка ввода/вывода, в т.ч. если поток прерван отменой задачи (подкласс {@link IOUtils.InterruptedStreamException})
     * @throws HttpRequestException если не удалось выполнить запрос, или содержимое отсутствует
     * @throws HttpWrongStatusCodeException если сервер вернул код не 200. При anycode==true будет содержать также HTML содержимое ответа.
     */
    public void downloadFileFromUrl(String url, OutputStream out, HttpRequestModel requestModel, HttpClient httpClient, ProgressListener listener,
            CancellableTask task, boolean anyCode) throws IOException, HttpRequestException, HttpWrongStatusCodeException {
        HttpResponseModel responseModel = null;
        try {
            responseModel = getFromUrl(url, requestModel, httpClient, listener, task);
            if (responseModel.statusCode == 200) {
                IOUtils.copyStream(responseModel.stream, out);
            } else {
                if (anyCode) {
                    byte[] html = null;
                    try {
                        ByteArrayOutputStream byteStream = new ByteArrayOutputStream(1024);
                        IOUtils.copyStream(responseModel.stream, byteStream);
                        html = byteStream.toByteArray();
                    } catch (Exception e) {
                        Logger.e(TAG, e);
                    }
                    throw new HttpWrongStatusCodeException(responseModel.statusCode, responseModel.statusCode+" - "+responseModel.statusReason, html);
                } else {
                    throw new HttpWrongStatusCodeException(responseModel.statusCode, responseModel.statusCode+" - "+responseModel.statusReason);
                }
            }
        } catch (Exception e) {
            if (responseModel != null) removeFromModifiedMap(url);
            throw e;
        } finally {
            if (responseModel != null) responseModel.release();
        }
    }
}
