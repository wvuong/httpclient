/*
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

package org.apache.http.impl.auth;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.FormattedHeader;
import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ParseException;
import org.apache.http.auth.AuthChallenge;
import org.apache.http.auth.AuthExchange;
import org.apache.http.auth.AuthScheme;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.ChallengeType;
import org.apache.http.auth.CredentialsProvider;
import org.apache.http.auth.MalformedChallengeException;
import org.apache.http.client.AuthCache;
import org.apache.http.client.AuthenticationStrategy;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.ParserCursor;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Asserts;
import org.apache.http.util.CharArrayBuffer;

/**
 * @since 4.3
 */
public class HttpAuthenticator {

    private final Log log;
    private final AuthChallengeParser parser;

    public HttpAuthenticator(final Log log) {
        super();
        this.log = log != null ? log : LogFactory.getLog(getClass());
        this.parser = new AuthChallengeParser();
    }

    public HttpAuthenticator() {
        this(null);
    }

    public boolean isChallenged(
            final HttpHost host,
            final ChallengeType challengeType,
            final HttpResponse response,
            final AuthExchange authExchange,
            final HttpContext context) {
        final int challengeCode;
        switch (challengeType) {
            case TARGET:
                challengeCode = HttpStatus.SC_UNAUTHORIZED;
                break;
            case PROXY:
                challengeCode = HttpStatus.SC_PROXY_AUTHENTICATION_REQUIRED;
                break;
            default:
                throw new IllegalStateException("Unexpected challenge type: " + challengeType);
        }

        final HttpClientContext clientContext = HttpClientContext.adapt(context);

        if (response.getStatusLine().getStatusCode() == challengeCode) {
            this.log.debug("Authentication required");
            if (authExchange.getState() == AuthExchange.State.SUCCESS) {
                clearCache(host, clientContext);
            }
            return true;
        } else {
            switch (authExchange.getState()) {
            case CHALLENGED:
            case HANDSHAKE:
                this.log.debug("Authentication succeeded");
                authExchange.setState(AuthExchange.State.SUCCESS);
                updateCache(host, authExchange.getAuthScheme(), clientContext);
                break;
            case SUCCESS:
                break;
            default:
                authExchange.setState(AuthExchange.State.UNCHALLENGED);
            }
            return false;
        }
    }

    public boolean prepareAuthResponse(
            final HttpHost host,
            final ChallengeType challengeType,
            final HttpResponse response,
            final AuthenticationStrategy authStrategy,
            final AuthExchange authExchange,
            final HttpContext context) {

        if (this.log.isDebugEnabled()) {
            this.log.debug(host.toHostString() + " requested authentication");
        }

        final HttpClientContext clientContext = HttpClientContext.adapt(context);

        final Header[] headers = response.getHeaders(
                challengeType == ChallengeType.PROXY ? HttpHeaders.PROXY_AUTHENTICATE : HttpHeaders.WWW_AUTHENTICATE);
        final Map<String, AuthChallenge> challengeMap = new HashMap<>();
        for (Header header: headers) {
            final CharArrayBuffer buffer;
            final int pos;
            if (header instanceof FormattedHeader) {
                buffer = ((FormattedHeader) header).getBuffer();
                pos = ((FormattedHeader) header).getValuePos();
            } else {
                final String s = header.getValue();
                if (s == null) {
                    continue;
                }
                buffer = new CharArrayBuffer(s.length());
                buffer.append(s);
                pos = 0;
            }
            final ParserCursor cursor = new ParserCursor(pos, buffer.length());
            final List<AuthChallenge> authChallenges;
            try {
                authChallenges = parser.parse(buffer, cursor);
            } catch (ParseException ex) {
                if (this.log.isWarnEnabled()) {
                    this.log.warn("Malformed challenge: " + header.getValue());
                }
                continue;
            }
            for (AuthChallenge authChallenge: authChallenges) {
                final String scheme = authChallenge.getScheme().toLowerCase(Locale.ROOT);
                if (!challengeMap.containsKey(scheme)) {
                    challengeMap.put(scheme, authChallenge);
                }
            }
        }
        if (challengeMap.isEmpty()) {
            this.log.debug("Response contains no valid authentication challenges");
            clearCache(host, clientContext);
            authExchange.reset();
            return false;
        }

        switch (authExchange.getState()) {
            case FAILURE:
                return false;
            case SUCCESS:
                authExchange.reset();
                break;
            case CHALLENGED:
            case HANDSHAKE:
                Asserts.notNull(authExchange.getAuthScheme(), "AuthScheme");
            case UNCHALLENGED:
                final AuthScheme authScheme = authExchange.getAuthScheme();
                if (authScheme != null) {
                    final String id = authScheme.getName();
                    final AuthChallenge challenge = challengeMap.get(id.toLowerCase(Locale.ROOT));
                    if (challenge != null) {
                        this.log.debug("Authorization challenge processed");
                        try {
                            authScheme.processChallenge(challenge, context);
                        } catch (MalformedChallengeException ex) {
                            if (this.log.isWarnEnabled()) {
                                this.log.warn(ex.getMessage());
                            }
                            clearCache(host, clientContext);
                            authExchange.reset();
                            return false;
                        }
                        if (authScheme.isChallengeComplete()) {
                            this.log.debug("Authentication failed");
                            clearCache(host, clientContext);
                            authExchange.reset();
                            authExchange.setState(AuthExchange.State.FAILURE);
                            return false;
                        } else {
                            authExchange.setState(AuthExchange.State.HANDSHAKE);
                            return true;
                        }
                    } else {
                        authExchange.reset();
                        // Retry authentication with a different scheme
                    }
                }
        }

        final List<AuthScheme> preferredSchemes = authStrategy.select(challengeType, challengeMap, context);
        final CredentialsProvider credsProvider = clientContext.getCredentialsProvider();
        if (credsProvider == null) {
            this.log.debug("Credentials provider not set in the context");
            return false;
        }

        final Queue<AuthScheme> authOptions = new LinkedList<>();
        for (AuthScheme authScheme: preferredSchemes) {
            try {
                final String id = authScheme.getName();
                final AuthChallenge challenge = challengeMap.get(id.toLowerCase(Locale.ROOT));
                authScheme.processChallenge(challenge, context);
                if (authScheme.isResponseReady(host, credsProvider, context)) {
                    authOptions.add(authScheme);
                }
            } catch (AuthenticationException | MalformedChallengeException ex) {
                if (this.log.isWarnEnabled()) {
                    this.log.warn(ex.getMessage());
                }
            }
        }
        if (!authOptions.isEmpty()) {
            if (this.log.isDebugEnabled()) {
                this.log.debug("Selected authentication options: " + authOptions);
            }
            authExchange.reset();
            authExchange.setState(AuthExchange.State.CHALLENGED);
            authExchange.setOptions(authOptions);
            return true;
        } else {
            return false;
        }
    }

    public void addAuthResponse(
            final HttpHost host,
            final ChallengeType challengeType,
            final HttpRequest request,
            final AuthExchange authExchange,
            final HttpContext context) throws HttpException, IOException {
        AuthScheme authScheme = authExchange.getAuthScheme();
        switch (authExchange.getState()) {
        case FAILURE:
            return;
        case SUCCESS:
            Asserts.notNull(authScheme, "AuthScheme");
            if (authScheme.isConnectionBased()) {
                return;
            }
            break;
        case HANDSHAKE:
            Asserts.notNull(authScheme, "AuthScheme");
            break;
        case CHALLENGED:
            final Queue<AuthScheme> authOptions = authExchange.getAuthOptions();
            if (authOptions != null) {
                while (!authOptions.isEmpty()) {
                    authScheme = authOptions.remove();
                    authExchange.select(authScheme);
                    if (this.log.isDebugEnabled()) {
                        this.log.debug("Generating response to an authentication challenge using "
                                + authScheme.getName() + " scheme");
                    }
                    try {
                        final String authResponse = authScheme.generateAuthResponse(host, request, context);
                        final Header header = new BasicHeader(
                                challengeType == ChallengeType.TARGET ? HttpHeaders.AUTHORIZATION : HttpHeaders.PROXY_AUTHORIZATION,
                                authResponse);
                        request.addHeader(header);
                        break;
                    } catch (final AuthenticationException ex) {
                        if (this.log.isWarnEnabled()) {
                            this.log.warn(authScheme + " authentication error: " + ex.getMessage());
                        }
                    }
                }
                return;
            } else {
                Asserts.notNull(authScheme, "AuthScheme");
            }
        default:
        }
        if (authScheme != null) {
            try {
                final String authResponse = authScheme.generateAuthResponse(host, request, context);
                final Header header = new BasicHeader(
                        challengeType == ChallengeType.TARGET ? HttpHeaders.AUTHORIZATION : HttpHeaders.PROXY_AUTHORIZATION,
                        authResponse);
                request.addHeader(header);
            } catch (final AuthenticationException ex) {
                if (this.log.isErrorEnabled()) {
                    this.log.error(authScheme + " authentication error: " + ex.getMessage());
                }
            }
        }
    }

    private boolean isCachable(final AuthScheme authScheme) {
        final String schemeName = authScheme.getName();
        return schemeName.equalsIgnoreCase(AuthSchemes.BASIC) ||
                schemeName.equalsIgnoreCase(AuthSchemes.DIGEST);
    }

    private void updateCache(final HttpHost host, final AuthScheme authScheme, final HttpClientContext clientContext) {
        if (isCachable(authScheme)) {
            AuthCache authCache = clientContext.getAuthCache();
            if (authCache == null) {
                authCache = new BasicAuthCache();
                clientContext.setAuthCache(authCache);
            }
            if (this.log.isDebugEnabled()) {
                this.log.debug("Caching '" + authScheme.getName() + "' auth scheme for " + host);
            }
            authCache.put(host, authScheme);
        }
    }

    private void clearCache(final HttpHost host, final HttpClientContext clientContext) {

        final AuthCache authCache = clientContext.getAuthCache();
        if (authCache != null) {
            if (this.log.isDebugEnabled()) {
                this.log.debug("Clearing cached auth scheme for " + host);
            }
            authCache.remove(host);
        }
    }

}
