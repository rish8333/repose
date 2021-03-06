/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package features.filters.keystonev2.serviceresponse

import org.joda.time.DateTime
import org.junit.experimental.categories.Category
import org.openrepose.commons.utils.http.HttpDate
import org.openrepose.framework.test.ReposeValveTest
import org.openrepose.framework.test.mocks.MockIdentityV2Service
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.MessageChain
import org.rackspace.deproxy.Response
import scaffold.category.Identity
import spock.lang.Unroll

import static javax.servlet.http.HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE
import static org.openrepose.commons.utils.http.normal.ExtendedStatusCodes.SC_TOO_MANY_REQUESTS
import static org.springframework.http.HttpHeaders.RETRY_AFTER

@Category(Identity)
class AuxiliaryErrorsTest extends ReposeValveTest {
    def static originEndpoint
    def static identityEndpoint

    static MockIdentityV2Service fakeIdentityV2Service

    def setupSpec() {
        deproxy = new Deproxy()

        def params = properties.defaultTemplateParams
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/filters/keystonev2/common", params)
        repose.configurationProvider.applyConfigs("features/filters/keystonev2/zerocachetime", params)
        repose.configurationProvider.applyConfigs("features/filters/keystonev2/connectionpooling", params)
        repose.start()

        originEndpoint = deproxy.addEndpoint(properties.targetPort, 'origin service')
        fakeIdentityV2Service = new MockIdentityV2Service(properties.identityPort, properties.targetPort)
        identityEndpoint = deproxy.addEndpoint(properties.identityPort,
                'identity service', null, fakeIdentityV2Service.handler)

    }

    def setup() {
        fakeIdentityV2Service.resetHandlers()
        waitForHttpClientRequestCacheToClear()
    }

    @Unroll("Identity Service Broken Admin Call: #adminBroken Broken Token Validation Call: #validateBroken Broken Groups Call: #groupsBroken Error Code: #errorCode")
    def "When the identity service endpoint returns failed or unexpected responses"() {

        given: "When Calls to Auth Return bad responses"
        if (adminBroken) {
            fakeIdentityV2Service.generateTokenHandler = { request, xml -> return new Response(errorCode) }
        }
        if (validateBroken) {
            fakeIdentityV2Service.validateTokenHandler = { tokenId, tenantId, request -> return new Response(errorCode) }
        }
        if (groupsBroken) {
            fakeIdentityV2Service.getGroupsHandler = { userId, request -> return new Response(errorCode) }
        }
        def tokenId = "$adminBroken$validateBroken$groupsBroken$errorCode"

        when: "User sends a request through repose"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint, method: 'GET', headers: ['X-Auth-Token': fakeIdentityV2Service.client_token + tokenId])

        then:
        "User should receive a " + expectedCode + "response"
        mc.receivedResponse.code == expectedCode


        where:
        adminBroken | validateBroken | groupsBroken | errorCode | expectedCode
        true        | false          | false        | 400       | "500"
        true        | false          | false        | 401       | "500"
        true        | false          | false        | 402       | "500"
        true        | false          | false        | 403       | "500"
        true        | false          | false        | 404       | "500"
        true        | false          | false        | 413       | "503"
        true        | false          | false        | 429       | "503"
        true        | false          | false        | 500       | "502"
        true        | false          | false        | 501       | "502"
        true        | false          | false        | 502       | "502"
        true        | false          | false        | 503       | "502"

        false       | true           | false        | 400       | "500"
        false       | true           | false        | 401       | "500"
        false       | true           | false        | 402       | "500"
        false       | true           | false        | 403       | "500"
        false       | true           | false        | 404       | "401"
        false       | true           | false        | 413       | "503"
        false       | true           | false        | 429       | "503"
        false       | true           | false        | 500       | "502"
        false       | true           | false        | 501       | "502"
        false       | true           | false        | 502       | "502"
        false       | true           | false        | 503       | "502"

        false       | false          | true         | 400       | "500"
        false       | false          | true         | 401       | "500"
        false       | false          | true         | 402       | "500"
        false       | false          | true         | 403       | "500"
        false       | false          | true         | 413       | "503"
        false       | false          | true         | 429       | "503"
        false       | false          | true         | 500       | "502"
        false       | false          | true         | 501       | "502"
        false       | false          | true         | 502       | "502"
        false       | false          | true         | 503       | "502"
        // REP-3212 changes-no longer an error but still leave here easy to track
        false       | false          | true         | 404       | "200"
    }


    @Unroll("Sending request with mock identity response set to HTTP #identityStatusCode and Retry-After header")
    def "when failing to authenticate client because of temporary failures"() {
        given: "the HTTP authentication header containing the client token and a Mock Identity return status of HTTP #identityStatusCode"

        def retryTimeStamp = DateTime.now().plusMinutes(5)
        def retryString = new HttpDate(retryTimeStamp.toGregorianCalendar().getTime()).toRFC1123()

        fakeIdentityV2Service.with {
            client_token = UUID.randomUUID().toString()
            validateTokenHandler = {
                tokenId, tenantId, request ->
                    new Response(identityStatusCode, null, [(retryAfterHeaderName): retryString])
            }
        }
        reposeLogSearch.cleanLog()

        when: "user passes a request through repose"
        MessageChain mc = deproxy.makeRequest(
                url: "$reposeEndpoint/servers/$reqTenant/",
                method: 'GET',
                headers: [
                        'content-type': 'application/json',
                        'X-Auth-Token': fakeIdentityV2Service.client_token
                ]
        )

        then: "request body sent from repose to the origin service should contain HTTP 503"
        mc.receivedResponse.code as Integer == SC_SERVICE_UNAVAILABLE
        mc.receivedResponse.getHeaders().getFirstValue(RETRY_AFTER).equals(retryString)
        reposeLogSearch.searchByString("Missing $RETRY_AFTER header on Auth Response status code: $identityStatusCode").size() == 0

        where:
        reqTenant | retryAfterHeaderName      | identityStatusCode
        1111      | RETRY_AFTER               | SC_REQUEST_ENTITY_TOO_LARGE
        1112      | RETRY_AFTER               | SC_TOO_MANY_REQUESTS
        1113      | RETRY_AFTER.toLowerCase() | SC_REQUEST_ENTITY_TOO_LARGE
        1114      | RETRY_AFTER.toLowerCase() | SC_TOO_MANY_REQUESTS
        1115      | RETRY_AFTER.toUpperCase() | SC_REQUEST_ENTITY_TOO_LARGE
        1116      | RETRY_AFTER.toUpperCase() | SC_TOO_MANY_REQUESTS
        1117      | "ReTrY-aFtEr"             | SC_REQUEST_ENTITY_TOO_LARGE
        1118      | "ReTrY-aFtEr"             | SC_TOO_MANY_REQUESTS
    }

}
