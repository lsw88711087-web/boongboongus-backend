package com.boongboongus.backend;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.boongboongus.backend.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ApiIntegrationTest.ValidationTestConfig.class)
class ApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthReturnsContractAndMatchingRequestId() throws Exception {
        var result = mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.meta.requestId", not(isEmptyOrNullString())))
                .andExpect(jsonPath("$.meta.serverTime", not(isEmptyOrNullString())))
                .andReturn();
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains(result.getResponse().getHeader("X-Request-Id"));
    }

    @Test
    void appConfigReturnsPublicPolicyWithoutAuthentication() throws Exception {
        mvc.perform(get("/api/v1/app-config"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty())
                .andExpect(jsonPath("$.meta.serverTime").isNotEmpty())
                .andExpect(jsonPath("$.data.apiVersion").value("v1"))
                .andExpect(jsonPath("$.data.contractVersion").isNotEmpty())
                .andExpect(jsonPath("$.data.policyVersion").isNotEmpty())
                .andExpect(jsonPath("$.data.policyStatus").value("APPROVED"))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.limits.nicknameMaxLength").value(20))
                .andExpect(jsonPath("$.data.limits.uploadMimeTypes[0]").value("image/jpeg"))
                .andExpect(jsonPath("$.data.features.coupleRequiredForDrive").value(true))
                .andExpect(jsonPath("$.data.features.freeDriveWithoutDestination").value(false))
                .andExpect(jsonPath("$.data.features.restAreaDetails").value(false))
                .andExpect(jsonPath("$.data.features.musicPlayback").value(false))
                .andExpect(jsonPath("$.data.authProviders[0]").value("KAKAO"))
                .andExpect(jsonPath("$.data.moods").isNotEmpty())
                .andExpect(jsonPath("$.data.weatherOptions").isNotEmpty())
                .andExpect(jsonPath("$.data.genres").isNotEmpty())
                .andExpect(jsonPath("$.data.gpsBatchMaxSamples").value(200))
                .andExpect(jsonPath("$.data.gpsLateUploadGraceSeconds").value(900))
                .andExpect(jsonPath("$.data.arrivalRadiusMeters").value(100))
                .andExpect(jsonPath("$.data.arrivalDwellSeconds").value(30))
                .andExpect(jsonPath("$.data.gameSetQuestionCount").value(5))
                .andExpect(jsonPath("$.data.defaultMascotName").value("붕붕이"));
    }

    @Test
    void unknownRouteUsesCommonErrorContract() throws Exception {
        mvc.perform(get("/api/v1/not-implemented"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void invalidBodyReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/test-validation").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"));
    }

    @Test
    void malformedJsonUsesCommonErrorContract() throws Exception {
        mvc.perform(post("/api/v1/test-validation").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    void corsAllowsConfiguredFrontendAndHeaders() throws Exception {
        mvc.perform(options("/api/v1/health")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Idempotency-Key,X-Couple-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void corsRejectsUnknownOrigin() throws Exception {
        mvc.perform(options("/api/v1/health")
                        .header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void openApiDocumentsImplementedRoutes() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/playlists'].post.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/jobs/{jobId}'].get.security[0].bearerAuth").isArray())
                .andExpect(jsonPath("$.paths['/api/v1/auth/token'].post.security").doesNotExist())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/health']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/app-config'].get").exists());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ValidationTestConfig {
        @Bean
        ValidationTestController validationTestController() {
            return new ValidationTestController();
        }
    }

    @RestController
    static class ValidationTestController {
        @PostMapping("/api/v1/test-validation")
        ApiResponse<NameInput> validate(@Valid @RequestBody NameInput input) {
            return ApiResponse.success(input);
        }
    }

    record NameInput(@NotBlank String name) {
    }
}
