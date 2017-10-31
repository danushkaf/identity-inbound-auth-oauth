/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.identity.openidconnect;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.oltu.oauth2.common.exception.OAuthSystemException;
import org.wso2.carbon.identity.application.common.IdentityApplicationManagementException;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.util.IdentityApplicationConstants;
import org.wso2.carbon.identity.application.mgt.ApplicationManagementService;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCache;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCacheEntry;
import org.wso2.carbon.identity.oauth.cache.AuthorizationGrantCacheKey;
import org.wso2.carbon.identity.oauth.user.UserInfoEndpointException;
import org.wso2.carbon.identity.oauth.user.UserInfoResponseBuilder;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.dto.OAuth2TokenValidationResponseDTO;
import org.wso2.carbon.identity.oauth2.internal.OAuth2ServiceComponentHolder;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OAuth20Params.USERINFO;

public abstract class AbstractUserInfoResponseBuilder implements UserInfoResponseBuilder {

    @Override
    public final String getResponseString(OAuth2TokenValidationResponseDTO tokenResponse)
            throws UserInfoEndpointException, OAuthSystemException {

        String spTenantDomain = getServiceProviderTenantDomain(tokenResponse);
        // Retrieve user claims.
        Map<String, Object> userClaims = retrieveUserClaims(tokenResponse);

        // Filter user claims based on the requested scopes
        Map<String, Object> filteredUserClaims =
                getUserClaimsFilteredByScope(tokenResponse.getScope(), spTenantDomain, userClaims);

        // Handle subject claim.
        String subjectClaim = getSubjectClaim(userClaims, spTenantDomain, tokenResponse);
        filteredUserClaims.put(OAuth2Util.SUB, subjectClaim);

        // Handle essential claims
        Map<String, Object> essentialClaims = getEssentialClaims(tokenResponse, userClaims);
        filteredUserClaims.putAll(essentialClaims);

        return buildResponse(tokenResponse, spTenantDomain, filteredUserClaims);
    }


    protected String getSubjectClaim(Map<String, Object> userClaims,
                                     String spTenantDomain,
                                     OAuth2TokenValidationResponseDTO tokenResponse) throws UserInfoEndpointException {

        String subjectClaim = (String) userClaims.get(OAuth2Util.SUB);
        if (StringUtils.isBlank(subjectClaim)) {
            // We need to send the tenant aware and user store domain removed name.
            String tenantAwareUsername = MultitenantUtils.getTenantAwareUsername(tokenResponse.getAuthorizedUser());
            subjectClaim = UserCoreUtil.removeDomainFromName(tenantAwareUsername);
        }
        return returnSubjectClaim(subjectClaim, spTenantDomain, tokenResponse);
    }

    private String returnSubjectClaim(String sub,
                                      String tenantDomain,
                                      OAuth2TokenValidationResponseDTO tokenResponse) throws UserInfoEndpointException {

        String clientId = getClientId(tokenResponse);
        ServiceProvider serviceProvider = getServiceProvider(tenantDomain, clientId);

        String userTenantDomain = MultitenantUtils.getTenantDomain(tokenResponse.getAuthorizedUser());
        String userStoreDomain = UserCoreUtil.extractDomainFromName(tokenResponse.getAuthorizedUser());

        if (serviceProvider != null) {
            boolean isUseTenantDomainInLocalSubject = serviceProvider.getLocalAndOutBoundAuthenticationConfig()
                    .isUseTenantDomainInLocalSubjectIdentifier();
            boolean isUseUserStoreDomainInLocalSubject = serviceProvider.getLocalAndOutBoundAuthenticationConfig()
                    .isUseUserstoreDomainInLocalSubjectIdentifier();

            if (StringUtils.isNotEmpty(sub)) {
                // building subject in accordance with Local and Outbound Authentication Configuration preferences
                if (isUseUserStoreDomainInLocalSubject) {
                    sub = UserCoreUtil.addDomainToName(sub, userStoreDomain);
                }
                if (isUseTenantDomainInLocalSubject) {
                    sub = UserCoreUtil.addTenantDomainToEntry(sub, userTenantDomain);
                }
            }
        }
        return sub;
    }

    private String getClientId(OAuth2TokenValidationResponseDTO tokenResponse) throws UserInfoEndpointException {
        String clientId;
        try {
            clientId = OAuth2Util.getClientIdForAccessToken
                    (tokenResponse.getAuthorizationContextToken().getTokenString());
        } catch (IdentityOAuth2Exception e) {
            throw new UserInfoEndpointException("Error while obtaining the client_id from accessToken.", e);
        }
        return clientId;
    }

    private ServiceProvider getServiceProvider(String tenantDomain, String clientId) throws UserInfoEndpointException {
        ApplicationManagementService applicationMgtService = OAuth2ServiceComponentHolder.getApplicationMgtService();
        ServiceProvider serviceProvider;
        try {
            //getting service provider
            serviceProvider = applicationMgtService.getServiceProviderByClientId(
                    clientId, IdentityApplicationConstants.OAuth2.NAME, tenantDomain);
        } catch (IdentityApplicationManagementException e) {
            throw new UserInfoEndpointException("Error while obtaining the service provider.", e);
        }
        return serviceProvider;
    }

    protected abstract Map<String, Object> retrieveUserClaims(OAuth2TokenValidationResponseDTO tokenValidationResponse)
            throws UserInfoEndpointException;


    protected Map<String, Object> getUserClaimsFilteredByScope(String[] requestedScopes,
                                                               String tenantDomain,
                                                               Map<String, Object> userClaims) {

        return OIDCClaimUtil.getClaimsFilteredByOIDCScopes(tenantDomain, requestedScopes, userClaims);
    }


    protected String getServiceProviderTenantDomain(OAuth2TokenValidationResponseDTO tokenResponse)
            throws UserInfoEndpointException {
        try {
            /*
                We can't get any information related to SP tenantDomain using the tokenResponse directly or indirectly.
                Therefore we make use of the thread local variable set at the UserInfo endpoint to get the tenantId
                of the service provider
             */
            int tenantId = OAuth2Util.getClientTenatId();
            return IdentityTenantUtil.getTenantDomain(tenantId);
        } finally {
            // clear the thread local that contained the SP tenantId
            OAuth2Util.clearClientTenantId();
        }
    }


    protected Map<String, Object> getEssentialClaims(OAuth2TokenValidationResponseDTO tokenResponse,
                                                     Map<String, Object> claims) throws UserInfoEndpointException {

        Map<String, Object> essentialClaimMap = new HashMap<>();
        List<String> essentialClaims = getEssentialClaimUris(tokenResponse);
        if (CollectionUtils.isNotEmpty(essentialClaims)) {
            for (String key : essentialClaims) {
                essentialClaimMap.put(key, claims.get(key));
            }
        }
        return essentialClaimMap;
    }

    private List<String> getEssentialClaimUris(OAuth2TokenValidationResponseDTO tokenResponse) {

        List<String> essentialClaims = Collections.emptyList();
        AuthorizationGrantCacheKey cacheKey =
                new AuthorizationGrantCacheKey(tokenResponse.getAuthorizationContextToken().getTokenString());

        AuthorizationGrantCacheEntry cacheEntry = AuthorizationGrantCache.getInstance()
                .getValueFromCacheByToken(cacheKey);

        if (cacheEntry != null) {
            if (StringUtils.isNotEmpty(cacheEntry.getEssentialClaims())) {
                essentialClaims = OAuth2Util.getEssentialClaims(cacheEntry.getEssentialClaims(), USERINFO);
            } else {
                essentialClaims = new ArrayList<>();
            }
        }
        return essentialClaims;
    }

    protected abstract String buildResponse(OAuth2TokenValidationResponseDTO tokenResponse,
                                            String spTenantDomain,
                                            Map<String, Object> userClaimsToBuildTheResponse) throws UserInfoEndpointException;
}
