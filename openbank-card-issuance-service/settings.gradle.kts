// SPDX-License-Identifier: Apache-2.0
rootProject.name = "openbank-card-issuance-service"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../openbank-libs/gradle/libs.versions.toml"))
        }
    }
}

includeBuild("../openbank-libs")
