package com.instapay.payment.adapters.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

  @Bean
  OpenAPI instaPayOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("InstaPay payment-service")
                .version("v1")
                .description(
                    "Errors use RFC 7807 (application/problem+json) with a stable `code`."));
  }

  /** The Idempotency-Key filter is invisible to springdoc; document it on every write. */
  @Bean
  OpenApiCustomizer idempotencyHeader() {
    return api ->
        api.getPaths()
            .values()
            .forEach(
                path ->
                    path.readOperations().stream()
                        .filter(
                            op ->
                                op == path.getPost()
                                    || op == path.getPut()
                                    || op == path.getPatch()
                                    || op == path.getDelete())
                        .forEach(
                            op ->
                                op.addParametersItem(
                                    new HeaderParameter()
                                        .name("Idempotency-Key")
                                        .required(true)
                                        .description(
                                            "Unique per operation; retries with the same key replay the first response")
                                        .schema(new StringSchema().maxLength(100)))));
  }
}
