/*
 * Copyright 2013-2014 the original author or authors.
 *
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
 */
package org.springframework.cloud.security.oauth2;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.oauth2.provider.token.DefaultTokenServices;
import org.springframework.security.oauth2.provider.token.RemoteTokenServices;
import org.springframework.security.oauth2.provider.token.ResourceServerTokenServices;
import org.springframework.security.oauth2.provider.token.TokenStore;
import org.springframework.security.oauth2.provider.token.store.JwtAccessTokenConverter;
import org.springframework.security.oauth2.provider.token.store.JwtTokenStore;
import org.springframework.social.connect.support.OAuth2ConnectionFactory;
import org.springframework.web.client.RestTemplate;

/**
 * @author Dave Syer
 *
 */
@Configuration
@EnableConfigurationProperties(ResourceServerProperties.class)
@Import(ClientConfiguration.class)
public class ResourceServerTokenServicesConfiguration {

	@Configuration
	@Conditional(NotJwtToken.class)
	protected static class RemoteTokenServicesConfiguration {

		@Configuration
		@Conditional(TokenInfo.class)
		protected static class TokenInfoServicesConfiguration {

			@Autowired
			private ResourceServerProperties resource;

			@Autowired
			private OAuth2ClientProperties client;

			@Bean
			protected RemoteTokenServices remoteTokenServices() {
				RemoteTokenServices services = new RemoteTokenServices();
				services.setCheckTokenEndpointUrl(resource.getTokenInfoUri());
				services.setClientId(client.getClientId());
				services.setClientSecret(client.getClientSecret());
				return services;
			}

		}

		@Configuration
		@ConditionalOnClass(OAuth2ConnectionFactory.class)
		@Conditional(NotTokenInfo.class)
		protected static class SocialTokenServicesConfiguration {

			@Autowired
			private ResourceServerProperties sso;

			@Autowired
			private OAuth2ClientProperties client;

			@Autowired(required = false)
			private OAuth2ConnectionFactory<?> connectionFactory;

			@Bean
			@ConditionalOnBean(OAuth2ConnectionFactory.class)
			@ConditionalOnMissingBean(ResourceServerTokenServices.class)
			public SpringSocialTokenServices socialTokenServices() {
				return new SpringSocialTokenServices(connectionFactory,
						client.getClientId());
			}

			@Bean
			@ConditionalOnMissingBean({ OAuth2ConnectionFactory.class,
					ResourceServerTokenServices.class })
			public UserInfoTokenServices userInfoTokenServices() {
				return new UserInfoTokenServices(sso.getUserInfoUri(),
						client.getClientId());
			}

		}

		@Configuration
		@ConditionalOnMissingClass(name = "org.springframework.social.connect.support.OAuth2ConnectionFactory")
		@Conditional(NotTokenInfo.class)
		protected static class UserInfoTokenServicesConfiguration {

			@Autowired
			private ResourceServerProperties sso;

			@Autowired
			private OAuth2ClientProperties client;

			@Bean
			@ConditionalOnMissingBean(ResourceServerTokenServices.class)
			public UserInfoTokenServices userInfoTokenServices() {
				return new UserInfoTokenServices(sso.getUserInfoUri(),
						client.getClientId());
			}

		}

	}

	@Configuration
	@Conditional(JwtToken.class)
	protected static class JwtTokenServicesConfiguration {

		@Autowired
		private ResourceServerProperties resource;

		@Bean
		public DefaultTokenServices jwtTokenServices() {
			DefaultTokenServices services = new DefaultTokenServices();
			services.setTokenStore(jwtTokenStore());
			// TODO: maybe add ClientDetailsService that only works for the configured client
			return services;
		}

		@Bean
		public TokenStore jwtTokenStore() {
			return new JwtTokenStore(jwtTokenEnhancer());
		}

		@Bean
		public JwtAccessTokenConverter jwtTokenEnhancer() {
			JwtAccessTokenConverter converter = new JwtAccessTokenConverter();
			String keyValue = resource.getJwt().getKeyValue();
			if (keyValue==null) {
				keyValue = (String) new RestTemplate().getForObject(resource.getJwt().getKeyUri(), Map.class).get("value");
			}
			converter.setVerifierKey(keyValue);
			return converter;
		}

	}

	private static class TokenInfo extends SpringBootCondition {

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context,
				AnnotatedTypeMetadata metadata) {
			if (context
					.getEnvironment()
					.resolvePlaceholders(
							"${oauth2.resource.preferTokenInfo:${OAUTH2_RESOURCE_PREFERTOKENINFO:true}}")
					.equals("true")) {
				return ConditionOutcome.match("Token info endpoint is preferred");
			}
			return ConditionOutcome.noMatch("Token info endpoint is not preferred");
		}

	}

	private static class JwtToken extends SpringBootCondition {

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context,
				AnnotatedTypeMetadata metadata) {
			if (context.getEnvironment().getProperty("oauth2.resource.jwt.keyValue") != null
					|| context.getEnvironment().getProperty("oauth2.resource.jwt.keyUri") != null) {
				return ConditionOutcome.match("Public key is provided");
			}
			return ConditionOutcome.noMatch("Public key is not provided");
		}

	}

	private static class NotTokenInfo extends SpringBootCondition {

		private TokenInfo opposite = new TokenInfo();

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context,
				AnnotatedTypeMetadata metadata) {
			ConditionOutcome outcome = opposite.getMatchOutcome(context, metadata);
			if (outcome.isMatch()) {
				return ConditionOutcome.noMatch(outcome.getMessage());
			}
			return ConditionOutcome.match(outcome.getMessage());
		}

	}

	private static class NotJwtToken extends SpringBootCondition {

		private JwtToken opposite = new JwtToken();

		@Override
		public ConditionOutcome getMatchOutcome(ConditionContext context,
				AnnotatedTypeMetadata metadata) {
			ConditionOutcome outcome = opposite.getMatchOutcome(context, metadata);
			if (outcome.isMatch()) {
				return ConditionOutcome.noMatch(outcome.getMessage());
			}
			return ConditionOutcome.match(outcome.getMessage());
		}

	}
}
