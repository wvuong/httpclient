/*
 * $HeadURL:$
 * $Revision:$
 * $Date:$
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.impl.cookie;

import java.util.ArrayList;
import java.util.List;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.apache.http.Header;
import org.apache.http.cookie.ClientCookie;
import org.apache.http.cookie.Cookie;
import org.apache.http.cookie.CookieOrigin;
import org.apache.http.cookie.CookieSpec;
import org.apache.http.cookie.MalformedCookieException;
import org.apache.http.message.BasicHeader;

/**
 * Test cases for 'best match' cookie policy
 *
 * @author <a href="mailto:oleg@ural.ru">Oleg Kalnichevski</a>
 * 
 * @version $Revision:$
 */
public class TestCookieBestMatchSpec extends TestCase {

    // ------------------------------------------------------------ Constructor

    public TestCookieBestMatchSpec(String name) {
        super(name);
    }

    // ------------------------------------------------------- TestCase Methods

    public static Test suite() {
        return new TestSuite(TestCookieBestMatchSpec.class);
    }

    public void testCookieBrowserCompatParsing() throws Exception {
        CookieSpec cookiespec = new BestMatchSpec();
        CookieOrigin origin = new CookieOrigin("a.b.domain.com", 80, "/", false);

        // Make sure the lenient (browser compatible) cookie parsing
        // and validation is used for Netscape style cookies
        Header header = new BasicHeader("Set-Cookie", "name=value;path=/;domain=domain.com");

        List<Cookie> cookies = cookiespec.parse(header, origin);
        for (int i = 0; i < cookies.size(); i++) {
            cookiespec.validate(cookies.get(i), origin);
        }
    }

    public void testNetscapeCookieParsing() throws Exception {
        CookieSpec cookiespec = new BestMatchSpec();
        CookieOrigin origin = new CookieOrigin("myhost.mydomain.com", 80, "/", false);

        Header header = new BasicHeader("Set-Cookie", 
            "name=value; path=/; domain=.mydomain.com; expires=Thu, 01-Jan-2070 00:00:10 GMT; comment=no_comment");
        List<Cookie> cookies = cookiespec.parse(header, origin);
        cookiespec.validate(cookies.get(0), origin);
        
        header = new BasicHeader("Set-Cookie", 
            "name=value; path=/; domain=.mydomain.com; expires=Thu, 01-Jan-2070 00:00:10 GMT; version=1");
        try {
            cookies = cookiespec.parse(header, origin);
            cookiespec.validate(cookies.get(0), origin);
            fail("MalformedCookieException exception should have been thrown");
        } catch (MalformedCookieException e) {
            // expected
        }
    }

    public void testCookieStandardCompliantParsing() throws Exception {
        CookieSpec cookiespec = new BestMatchSpec();
        CookieOrigin origin = new CookieOrigin("a.b.domain.com", 80, "/", false);

        // Make sure the strict (RFC2965) cookie parsing
        // and validation is used for version 1 cookies
        Header header = new BasicHeader("Set-Cookie", "name=value;path=/;domain=b.domain.com; version=1");

        List<Cookie> cookies = cookiespec.parse(header, origin);
        for (int i = 0; i < cookies.size(); i++) {
            cookiespec.validate(cookies.get(i), origin);
        }

        header = new BasicHeader("Set-Cookie", "name=value;path=/;domain=domain.com; version=1");
        try {
            cookies = cookiespec.parse(header, origin);
            cookiespec.validate(cookies.get(0), origin);
            fail("MalformedCookieException exception should have been thrown");
        } catch (MalformedCookieException e) {
            // expected
        }
    }

    public void testCookieBrowserCompatMatch() throws Exception {
        CookieSpec cookiespec = new BestMatchSpec();
        CookieOrigin origin = new CookieOrigin("a.b.domain.com", 80, "/", false);

        // Make sure the lenient (browser compatible) cookie matching
        // is used for Netscape style cookies
        BasicClientCookie cookie = new BasicClientCookie("name", "value");
        cookie.setDomain(".domain.com");
        cookie.setAttribute(ClientCookie.DOMAIN_ATTR, cookie.getDomain());
        cookie.setPath("/");
        cookie.setAttribute(ClientCookie.PATH_ATTR, cookie.getPath());

        assertTrue(cookiespec.match(cookie, origin));
    }

    public void testCookieStandardCompliantMatch() throws Exception {
        CookieSpec cookiespec = new BestMatchSpec();
        CookieOrigin origin = new CookieOrigin("a.b.domain.com", 80, "/", false);

        // Make sure the strict (RFC2965) cookie matching
        // is used for version 1 cookies
        BasicClientCookie cookie = new BasicClientCookie("name", "value");
        cookie.setVersion(1);
        cookie.setDomain(".domain.com");
        cookie.setAttribute(ClientCookie.DOMAIN_ATTR, cookie.getDomain());
        cookie.setPath("/");
        cookie.setAttribute(ClientCookie.PATH_ATTR, cookie.getPath());

        assertFalse(cookiespec.match(cookie, origin));

        cookie.setDomain(".b.domain.com");
        
        assertTrue(cookiespec.match(cookie, origin));
    }
    
    public void testCookieBrowserCompatFormatting() throws Exception {
        CookieSpec cookiespec = new BestMatchSpec();

        // Make sure the lenient (browser compatible) cookie formatting
        // is used for Netscape style cookies
        BasicClientCookie cookie1 = new BasicClientCookie("name1", "value1");
        cookie1.setDomain(".domain.com");
        cookie1.setAttribute(ClientCookie.DOMAIN_ATTR, cookie1.getDomain());
        cookie1.setPath("/");
        cookie1.setAttribute(ClientCookie.PATH_ATTR, cookie1.getPath());

        BasicClientCookie cookie2 = new BasicClientCookie("name2", "value2");
        cookie2.setVersion(1);
        cookie2.setDomain(".domain.com");
        cookie2.setAttribute(ClientCookie.DOMAIN_ATTR, cookie2.getDomain());
        cookie2.setPath("/");
        cookie2.setAttribute(ClientCookie.PATH_ATTR, cookie2.getPath());
     
        List<Cookie> cookies = new ArrayList<Cookie>();
        cookies.add(cookie1);
        cookies.add(cookie2);
        
        List<Header> headers = cookiespec.formatCookies(cookies);
        assertNotNull(headers);
        assertEquals(1, headers.size());
        
        Header header = headers.get(0);
        assertEquals("name1=value1; name2=value2", header.getValue());
        
    }

    public void testCookieStandardCompliantFormatting() throws Exception {
        CookieSpec cookiespec = new BestMatchSpec(null, true);

        // Make sure the strict (RFC2965) cookie formatting
        // is used for Netscape style cookies
        BasicClientCookie cookie1 = new BasicClientCookie("name1", "value1");
        cookie1.setVersion(1);
        cookie1.setDomain(".domain.com");
        cookie1.setAttribute(ClientCookie.DOMAIN_ATTR, cookie1.getDomain());
        cookie1.setPath("/");
        cookie1.setAttribute(ClientCookie.PATH_ATTR, cookie1.getPath());

        BasicClientCookie cookie2 = new BasicClientCookie("name2", "value2");
        cookie2.setVersion(1);
        cookie2.setDomain(".domain.com");
        cookie2.setAttribute(ClientCookie.DOMAIN_ATTR, cookie2.getDomain());
        cookie2.setPath("/");
        cookie2.setAttribute(ClientCookie.PATH_ATTR, cookie2.getPath());
     
        List<Cookie> cookies = new ArrayList<Cookie>();
        cookies.add(cookie1);
        cookies.add(cookie2);
        
        List<Header> headers = cookiespec.formatCookies(cookies);
        assertNotNull(headers);
        assertEquals(1, headers.size());
        
        Header header = headers.get(0);
        assertEquals("$Version=1; name1=\"value1\"; $Path=\"/\"; $Domain=\".domain.com\"; " +
        		"name2=\"value2\"; $Path=\"/\"; $Domain=\".domain.com\"", 
        		header.getValue());
        
    }

    public void testInvalidInput() throws Exception {
        CookieSpec cookiespec = new BestMatchSpec();
        try {
            cookiespec.parse(null, null);
            fail("IllegalArgumentException must have been thrown");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            cookiespec.parse(new BasicHeader("Set-Cookie", "name=value"), null);
            fail("IllegalArgumentException must have been thrown");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            cookiespec.formatCookies(null);
            fail("IllegalArgumentException must have been thrown");
        } catch (IllegalArgumentException ex) {
            // expected
        }
        try {
            List<Cookie> cookies = new ArrayList<Cookie>();
            cookiespec.formatCookies(cookies);
            fail("IllegalArgumentException must have been thrown");
        } catch (IllegalArgumentException ex) {
            // expected
        }
    }
    
}

