package org.eclipse.jetty.client;

import java.io.EOFException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class HttpReceiverTest
{
    private HttpClient client;
    private HttpDestination destination;
    private ByteArrayEndPoint endPoint;
    private HttpConnection connection;
    private HttpSender sender;
    private HttpReceiver receiver;
    private HttpConversation conversation;

    @Before
    public void init() throws Exception
    {
        client = new HttpClient();
        client.start();
        destination = new HttpDestination(client, "http", "localhost", 8080);
        endPoint = new ByteArrayEndPoint();
        connection = new HttpConnection(client, endPoint, destination);
        sender = new HttpSender(connection);
        receiver = new HttpReceiver(connection);
        conversation = new HttpConversation(client, 1);
    }

    @After
    public void destroy() throws Exception
    {
        client.stop();
    }

    protected HttpExchange newExchange(Response.Listener listener)
    {
        HttpExchange exchange = new HttpExchange(conversation, sender, receiver, null, listener);
        conversation.add(exchange);
        return exchange;
    }

    @Test
    public void test_Receive_NoResponseContent() throws Exception
    {
        final AtomicReference<Response> responseRef = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        HttpExchange exchange = newExchange(new Response.Listener.Adapter()
        {
            @Override
            public void onSuccess(Response response)
            {
                responseRef.set(response);
                latch.countDown();
            }
        });

        endPoint.setInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-length: 0\r\n" +
                "\r\n");
        receiver.receive(exchange);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        Response response = responseRef.get();
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertEquals("OK", response.reason());
        Assert.assertSame(HttpVersion.HTTP_1_1, response.version());
        HttpFields headers = response.headers();
        Assert.assertNotNull(headers);
        Assert.assertEquals(1, headers.size());
        Assert.assertEquals("0", headers.get(HttpHeader.CONTENT_LENGTH));
    }

    @Test
    public void test_Receive_ResponseContent() throws Exception
    {
        String content = "0123456789ABCDEF";
        endPoint.setInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-length: " + content.length() + "\r\n" +
                "\r\n" +
                content);
        BufferingResponseListener listener = new BufferingResponseListener();
        HttpExchange exchange = newExchange(listener);
        receiver.receive(exchange);

        Response response = listener.await(5, TimeUnit.SECONDS);
        Assert.assertNotNull(response);
        Assert.assertEquals(200, response.status());
        Assert.assertEquals("OK", response.reason());
        Assert.assertSame(HttpVersion.HTTP_1_1, response.version());
        HttpFields headers = response.headers();
        Assert.assertNotNull(headers);
        Assert.assertEquals(1, headers.size());
        Assert.assertEquals(String.valueOf(content.length()), headers.get(HttpHeader.CONTENT_LENGTH));
        String received = listener.contentAsString("UTF-8");
        Assert.assertEquals(content, received);
    }

    @Test
    public void test_Receive_ResponseContent_EarlyEOF() throws Exception
    {
        String content1 = "0123456789";
        String content2 = "ABCDEF";
        endPoint.setInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-length: " + (content1.length() + content2.length()) + "\r\n" +
                "\r\n" +
                content1);
        BufferingResponseListener listener = new BufferingResponseListener();
        HttpExchange exchange = newExchange(listener);
        receiver.receive(exchange);
        endPoint.setInputEOF();
        receiver.receive(exchange);

        try
        {
            listener.await(5, TimeUnit.SECONDS);
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Assert.assertTrue(e.getCause() instanceof EOFException);
        }
    }

    @Test
    public void test_Receive_ResponseContent_IdleTimeout() throws Exception
    {
        endPoint.setInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-length: 1\r\n" +
                "\r\n");
        BufferingResponseListener listener = new BufferingResponseListener();
        HttpExchange exchange = newExchange(listener);
        receiver.receive(exchange);
        // Simulate an idle timeout
        receiver.idleTimeout();

        try
        {
            listener.await(5, TimeUnit.SECONDS);
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Assert.assertTrue(e.getCause() instanceof TimeoutException);
        }
    }

    @Test
    public void test_Receive_BadResponse() throws Exception
    {
        endPoint.setInput("" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-length: A\r\n" +
                "\r\n");
        BufferingResponseListener listener = new BufferingResponseListener();
        HttpExchange exchange = newExchange(listener);
        receiver.receive(exchange);

        try
        {
            listener.await(5, TimeUnit.SECONDS);
            Assert.fail();
        }
        catch (ExecutionException e)
        {
            Assert.assertTrue(e.getCause() instanceof HttpResponseException);
        }
    }
}
