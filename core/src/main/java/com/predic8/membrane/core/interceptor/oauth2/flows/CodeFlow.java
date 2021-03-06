/*
 * Copyright 2016 predic8 GmbH, www.predic8.com
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.predic8.membrane.core.interceptor.oauth2.flows;

import com.predic8.membrane.core.exchange.Exchange;
import com.predic8.membrane.core.http.Response;
import com.predic8.membrane.core.interceptor.Outcome;
import com.predic8.membrane.core.interceptor.authentication.session.SessionManager;
import com.predic8.membrane.core.interceptor.oauth2.OAuth2AuthorizationServerInterceptor;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.SecureRandom;

public class CodeFlow extends OAuth2Flow{

    public CodeFlow(OAuth2AuthorizationServerInterceptor authServer, Exchange exc, SessionManager.Session s) {
        super(authServer, exc, s);
    }

    public Outcome getResponse() throws UnsupportedEncodingException {
        String code = generateAuthorizationCode();
        authServer.getSessionFinder().addSessionForCode(code,session);
        return respondWithAuthorizationCodeAndRedirect(exc, code, session);
    }

    protected static String generateAuthorizationCode() {
        return new BigInteger(130, new SecureRandom()).toString(32);
    }

    protected Outcome respondWithAuthorizationCodeAndRedirect(Exchange exc, String code, SessionManager.Session s) throws UnsupportedEncodingException {
        String state;
        String redirectUrl;
        synchronized (s) {
            state = s.getUserAttributes().get("state");
            redirectUrl = s.getUserAttributes().get("redirect_uri");
        }

        exc.setResponse(Response.
                redirect(redirectUrl + "?code=" + code + stateQuery(state), false).
                dontCache().
                body("").
                build());
        
        return Outcome.RETURN;
    }
}
