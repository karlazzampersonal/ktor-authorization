# Role-based authorization in Ktor
This repository forked the original role based authorization feature and added a couple of additions:

- Support for RBAC for JWT principals, with currently 1 implementation (Keycloak)
- Publishing it as a public package in GH packages

## Add package
<details><summary>Set up in Kotlin Gradle:</summary>

```kotlin
repositories {
    mavenCentral()
    // Need a GH access token with read package scope
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/karlazzampersonal/ktor-authorization")
        credentials {
            username = props.getProperty("USERNAME")
            password = props.getProperty("TOKEN")
        }
    }
}

dependencies {
    implementation("com.levels:ktor-authorization:$ktor_authorization_version")
}
```
</details>

## Configure keycloak

Let's assume we have:
1. A keycloak realm named levels
2. We are running keycloak locally on localhost:8080
3. We have a client named ktor with a role added named user


<img width="400" alt="Screen Shot 2022-02-21 at 10 05 08 AM" src="https://user-images.githubusercontent.com/96435669/154981014-98281824-280a-44e2-8274-d69eba25ad59.png">

4. We create a group named user-group and make it the default group
5. We assign a role mapping for the client ktor for the role user
6. Each time we create a user in keycloak we assign to the user-group

<img width="600" alt="Screen Shot 2022-02-21 at 10 06 20 AM" src="https://user-images.githubusercontent.com/96435669/154981221-031fbbfd-dc46-4408-b52d-1cfbaa473fa4.png">

This means that every time a user logs in, the JWT claim will have the user role assigned:
(Specifically look at the roles array below under resource_access for the client ktor)

You can see the role(s) assigned is user for the client ktor
```json
{
  "exp": 1645455039,
  "iat": 1645454739,
  "jti": "4e986132-1c5b-4af7-b216-9b3cda7558bf",
  "iss": "http://localhost:8080/auth/realms/levels",
  "aud": "account",
  "sub": "f1b3ec3f-3835-4eff-9a29-958734c448ec",
  "typ": "Bearer",
  "azp": "user",
  "session_state": "eedd7908-1a64-499f-a621-2e3c8b4f6ffd",
  "acr": "1",
  "realm_access": {
    "roles": [
      "offline_access",
      "default-roles-levels",
      "uma_authorization"
    ]
  },
  "resource_access": {
    "ktor": {
      "roles": [
        "user"
      ]
    },
    "account": {
      "roles": [
        "manage-account",
        "manage-account-links",
        "view-profile"
      ]
    }
  },
  "scope": "email profile",
  "email_verified": false,
  "preferred_username": "karltest@gmail.com"
}
```

## Configure ktor

Let's only allow access for users with the role user:

First, Add the RoleBasedAuthorization feature to your Application module

You need to set authenticationType to JWT since we're dealing with JWTs here.
Set the issuer type to KEYCLOAK since we're using Keycloak. 
Set the client to whichever client you created, in my case it's named ktor.
Finally, set the roles you have for this client, in my case I have one role named user,
but if you have multiple roles, i.e [ user, admin, etc] then specify them all.

```kotlin
// Get the vault token and engine from environment variables
install(RoleBasedAuthorization) {
    authenticationType = JWT
    issuerType = KEYCLOAK
    client = "ktor"
    getRoles { setOf("user") }
}
```

Next, add the ktor auth and ktor jwt dependencies to your gradle file
```kotlin
implementation("io.ktor:ktor-auth:$ktor_version")
implementation("io.ktor:ktor-auth-jwt:$ktor_version")
```

Install the Authentication feature to your application module

This feature verifies the JWT by signing it using the JWK endpoint, in keycloak the url is `/auth/realms/{myrealm}/protocol/openid-connect/certs`

The `jwkProvider` object contains a cached URL of the JWK endpoint, in the code below its cached for 24 hrs.

The verifier function verifies the token ensuring it's not expired and by invoking the JWK url to sign it.

The validate function does nothing but return the JWTPrincipal, but you can add extra logic here if you want.
```kotlin
val jwkProvider = JwkProviderBuilder(URL("http://localhost:8080/auth/realms/levels/protocol/openid-connect/certs"))
    .cached(10, 24, TimeUnit.HOURS)
    .rateLimited(10, 1, TimeUnit.MINUTES)
    .build()

install(Authentication) {
    jwt("auth-jwt") {
        verifier(jwkProvider, "http://localhost:8080/auth/realms/levels") {
            acceptLeeway(3)
        }
        validate { credential ->
          JWTPrincipal(credential.payload)
        }
    }
}
```

Wrap your route with the authenticate route and then inside that, the withRole route
```kotlin
authenticate("auth-jwt") {
    withRole("user") {
        get("/role-user-required") {
            call.respondText(text = "Ok", status = HttpStatusCode.OK)
        }
    }
}
```
Last, but not least, install the status pages feature to handle the authorization exception
```kotlin
install(StatusPages) {
    exception<AuthorizationException> {
       call.respondText(text = "Invalid role", status = HttpStatusCode.Forbidden)
    }
}
```
