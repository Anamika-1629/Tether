package com.tether.auth.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Interactive docs at /swagger-ui.html. The "Authorize" button takes the accessToken from login. */
@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI tetherAuthApi() {
        return new OpenAPI()
                .info(new Info().title("Tether Auth / Tenant Service").version("0.1.0")
                        .description("Register, log in and get the JWT every Tether service trusts."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
