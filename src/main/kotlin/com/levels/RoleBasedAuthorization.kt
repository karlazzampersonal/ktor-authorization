/*
 *    Copyright 2020 Ximedes BV
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.levels

import com.auth0.jwt.interfaces.Payload
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.auth.jwt.*
import io.ktor.request.*
import io.ktor.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory

typealias Role = String

private val log: Logger = LoggerFactory.getLogger("com.levels.ktor.RoleBasedAuthorization")

class AuthorizationException(override val message: String) : Exception(message)

enum class AuthenticationType { JWT, OTHER }
enum class IssuerType { KEYCLOAK, OTHER }

class RoleBasedAuthorization(config: Configuration) {
    private val getRoles = config._getRoles
    private val issuerType = config.issuerType
    private val authenticationType = config.authenticationType
    private val client = config.client

    class Configuration {
        internal var _getRoles: (Principal) -> Set<Role> = { emptySet() }
        var issuerType: IssuerType = IssuerType.OTHER
        var authenticationType: AuthenticationType = AuthenticationType.OTHER
        var client: String = ""

        fun getRoles(gr: (Principal) -> Set<Role>) {
            _getRoles = gr
        }
    }

    fun interceptPipeline(
        pipeline: ApplicationCallPipeline,
        any: Set<Role>? = null,
        all: Set<Role>? = null,
        none: Set<Role>? = null
    ) {
        pipeline.insertPhaseAfter(ApplicationCallPipeline.Features, Authentication.ChallengePhase)
        pipeline.insertPhaseAfter(Authentication.ChallengePhase, AuthorizationPhase)

        pipeline.intercept(AuthorizationPhase) {
            val principal: Principal
            val roles = when (authenticationType) {
                AuthenticationType.JWT -> {
                    principal =
                        call.authentication.principal<JWTPrincipal>() ?: throw AuthorizationException("Missing principal")
                    when (issuerType) {
                        IssuerType.KEYCLOAK -> { getKeycloakRoles(principal.payload, client) }
                        else -> getRoles(principal)
                    }
                }
                AuthenticationType.OTHER -> {
                    principal =
                        call.authentication.principal() ?: throw AuthorizationException("Missing principal")
                    getRoles(principal)
                }
            }
            val denyReasons = mutableListOf<String>()
            all?.let {
                val missing = all - roles
                if (missing.isNotEmpty()) {
                    denyReasons += "Principal $principal lacks required role(s) ${missing.joinToString(" and ")}"
                }
            }
            any?.let {
                if (any.none { it in roles }) {
                    denyReasons += "Principal $principal has none of the sufficient role(s) ${
                    any.joinToString(
                        " or "
                    )
                    }"
                }
            }
            none?.let {
                if (none.any { it in roles }) {
                    denyReasons += "Principal $principal has forbidden role(s) ${
                    (none.intersect(roles)).joinToString(
                        " and "
                    )
                    }"
                }
            }
            if (denyReasons.isNotEmpty()) {
                val message = denyReasons.joinToString(". ")
                log.error("Authorization failed for ${call.request.path()}. $message")
                throw AuthorizationException(message)
            }
        }
    }

    companion object Feature : ApplicationFeature<ApplicationCallPipeline, Configuration, RoleBasedAuthorization> {
        override val key = AttributeKey<RoleBasedAuthorization>("RoleBasedAuthorization")

        val AuthorizationPhase = PipelinePhase("Authorization")

        override fun install(
            pipeline: ApplicationCallPipeline,
            configure: Configuration.() -> Unit
        ): RoleBasedAuthorization {
            val configuration = Configuration().apply(configure)
            return RoleBasedAuthorization(configuration)
        }
    }
}

class AuthorizedRouteSelector(private val description: String) : RouteSelector() {
    override fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Constant

    override fun toString(): String = "(authorize $description)"
}

fun Route.withRole(role: Role, build: Route.() -> Unit) = authorizedRoute(all = setOf(role), build = build)

fun Route.withAllRoles(vararg roles: Role, build: Route.() -> Unit) =
    authorizedRoute(all = roles.toSet(), build = build)

fun Route.withAnyRole(vararg roles: Role, build: Route.() -> Unit) = authorizedRoute(any = roles.toSet(), build = build)

fun Route.withoutRoles(vararg roles: Role, build: Route.() -> Unit) =
    authorizedRoute(none = roles.toSet(), build = build)

private fun Route.authorizedRoute(
    any: Set<Role>? = null,
    all: Set<Role>? = null,
    none: Set<Role>? = null,
    build: Route.() -> Unit
): Route {

    val description = listOfNotNull(
        any?.let { "anyOf (${any.joinToString(" ")})" },
        all?.let { "allOf (${all.joinToString(" ")})" },
        none?.let { "noneOf (${none.joinToString(" ")})" }
    ).joinToString(",")
    val authorizedRoute = createChild(AuthorizedRouteSelector(description))
    application.feature(RoleBasedAuthorization).interceptPipeline(authorizedRoute, any, all, none)
    authorizedRoute.build()
    return authorizedRoute
}

private fun getKeycloakRoles(payload: Payload, client: String): Set<Role> {
    val json = Json.parseToJsonElement(payload.getClaim("resource_access").toString())
    val jsonArray = json.jsonObject[client]!!.jsonObject["roles"]!!.jsonArray
    return jsonArray.map { it.toString().removeSurrounding("\"") }.toSet()
}
