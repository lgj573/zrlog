package com.fzb.blog.web.handler;

import com.fzb.blog.util.ZrlogUtil;
import com.fzb.blog.web.util.WebTools;
import com.fzb.common.util.IOUtil;
import com.fzb.common.util.http.HttpUtil;
import com.fzb.common.util.http.handle.CloseResponseHandle;
import com.jfinal.handler.Handler;
import com.jfinal.kit.PathKit;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.*;
import java.io.*;
import java.util.*;

/**
 * 用于对静态文件的请求的检查，和静态化文章页，加快文章页的响应。
 */
public class StaticFileCheckHandler extends Handler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StaticFileCheckHandler.class);
    private static final String[] START_TRIM_AFTER = {"<html", "</textarea", "</pre", "</script"};
    private static final String[] STOP_TRIM_AFTER = {"</html", "<textarea", "<pre", "<script"};

    //不希望部分技术人走后门，拦截一些不合法的请求
    private static final Set<String> FORBIDDEN_URI_EXT_SET = new HashSet<String>();

    static {
        //由于程序的.jsp文件没有存放在WEB-INF目录，为了防止访问.jsp页面获得的没有数据的页面，或则是错误的页面。
        FORBIDDEN_URI_EXT_SET.add(".jsp");
        //这主要用在主题目录下面的配置文件。
        FORBIDDEN_URI_EXT_SET.add(".properties");
    }

    public void handle(String target, HttpServletRequest request,
                       HttpServletResponse response, boolean[] isHandled) {
        String ext = null;
        if (target.contains("/")) {
            String name = target.substring(target.lastIndexOf('/'));
            if (name.contains(".")) {
                ext = name.substring(name.lastIndexOf('.'));
            }
        }
        if (ext != null) {
            if (!FORBIDDEN_URI_EXT_SET.contains(ext)) {
                // 处理静态化文件,仅仅缓存文章页(变化较小)
                if (target.endsWith(".html") && target.startsWith("/post/")) {
                    try {
                        String path = new String(request.getServletPath().getBytes("ISO-8859-1"), "UTF-8");
                        File htmlFile = new File(PathKit.getWebRootPath() + path);
                        isHandled[0] = true;
                        if (!htmlFile.exists()) {
                            String home = WebTools.getRealScheme(request) + "://" + request.getHeader("host")
                                    + request.getContextPath() + path.substring(0, path.lastIndexOf("."));
                            if (!ZrlogUtil.isStaticBlogPlugin(request)) {
                                Map<String, String> requestHeaders = new HashMap<>();
                                Enumeration<String> headerNames = request.getHeaderNames();
                                while (headerNames.hasMoreElements()) {
                                    String key = headerNames.nextElement();
                                    requestHeaders.put(key, request.getHeader(key));
                                }
                                convert2Html(home, htmlFile, requestHeaders);
                            }
                        }
                        response.setContentType("text/html;charset=UTF-8");
                        response.getOutputStream().write(IOUtil.getByteByInputStream(new FileInputStream(htmlFile)));
                        this.next.handle(target, request, response, isHandled);
                    } catch (Exception e) {
                        LOGGER.error("error", e);
                    }
                } else {
                    this.next.handle(target, request, response, isHandled);
                }
            } else {
                try {
                    //非法请求, 返回403
                    request.getSession();
                    response.sendError(403);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else {
            HttpServletResponseWrapper responseWrapper = new HttpServletResponseWrapper(response) {

                @Override
                public PrintWriter getWriter() throws IOException {
                    return createTrimWriter(this);
                }
            };
            this.next.handle(target, request, responseWrapper, isHandled);
        }
    }

    private static PrintWriter createTrimWriter(final HttpServletResponse response)
            throws IOException {
        return new PrintWriter(new OutputStreamWriter(response.getOutputStream(), "UTF-8"), true) {
            private StringBuilder builder = new StringBuilder();
            private boolean trim = false;

            public void write(int c) {
                builder.append((char) c); // It is actually a char, not an int.
            }

            public void write(char[] chars, int offset, int length) {
                builder.append(chars, offset, length);
                this.flush(); // Preflush it.
            }

            public void write(String string, int offset, int length) {
                builder.append(string, offset, length);
                this.flush(); // Preflush it.
            }

            // Finally override the flush method so that it trims whitespace.
            public void flush() {
                synchronized (builder) {
                    BufferedReader reader = new BufferedReader(new StringReader(builder.toString()));
                    String line;

                    try {
                        while ((line = reader.readLine()) != null) {
                            if (startTrim(line)) {
                                trim = true;
                                out.write(line);
                            } else if (trim) {
                                out.write(line.trim());
                                if (stopTrim(line)) {
                                    trim = false;
                                    println();
                                }
                            } else {
                                out.write(line);
                                println();
                            }
                        }
                    } catch (IOException e) {
                        setError();
                        // Log e or do e.printStackTrace() if necessary.
                    }

                    // Reset the local StringBuilder and issue real flush.
                    builder = new StringBuilder();
                    super.flush();
                }
            }

            private boolean startTrim(String line) {
                for (String match : START_TRIM_AFTER) {
                    if (line.contains(match)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean stopTrim(String line) {
                for (String match : STOP_TRIM_AFTER) {
                    if (line.contains(match)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /**
     * 将一个网页转化对应文件，用于静态化文章页
     *
     * @param sSourceUrl
     * @param file
     */
    private byte[] convert2Html(String sSourceUrl, File file, Map<String, String> requestHeaders) {
        if (!file.exists()) {
            file.getParentFile().mkdirs();
        }
        try {
            CloseableHttpResponse closeableHttpResponse = HttpUtil.getInstance().sendGetRequest(sSourceUrl, new CloseResponseHandle(), requestHeaders).getT();
            if (closeableHttpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
                String str = IOUtil.getStringInputStream(closeableHttpResponse.getEntity().getContent());
                IOUtil.writeBytesToFile(str.getBytes("UTF-8"), file);
                return str.getBytes();
            }
        } catch (IOException e) {
            LOGGER.error("convert2Html error", e);
        }
        return new byte[]{};
    }
}
