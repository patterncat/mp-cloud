package demo;

import java.io.IOException;
import java.security.Principal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import demo.filter.SimpleFilter;
import demo.filter.TokenGeneratorFilter;
import demo.security.CustomUserDetailsService;
import demo.security.jwt.TokenAuthenticationService;
import demo.security.jwt.UserService;

import org.jasig.cas.client.session.SingleSignOutFilter;
import org.jasig.cas.client.validation.Cas20ServiceTicketValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.cloud.netflix.zuul.EnableZuulProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.cas.ServiceProperties;
import org.springframework.security.cas.authentication.CasAssertionAuthenticationToken;
import org.springframework.security.cas.authentication.CasAuthenticationProvider;
import org.springframework.security.cas.web.CasAuthenticationEntryPoint;
import org.springframework.security.cas.web.CasAuthenticationFilter;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.AuthenticationUserDetailsService;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.session.SessionManagementFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

@Configuration
@ComponentScan
@EnableAutoConfiguration
@Controller
@EnableRedisHttpSession
@EnableZuulProxy
public class GatewayApplication {

    @RequestMapping("/user")
    @ResponseBody
    public Map<String, Object> user(Principal user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", user.getName());
        map.put("roles", AuthorityUtils.authorityListToSet(((Authentication) user).getAuthorities()));
        return map;
    }

//    @RequestMapping("/login")
//    public String login() {
//        return "forward:/";
//    }

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    public SimpleFilter zuulMapping() {
        SimpleFilter filter = new SimpleFilter();
        return filter;
    }

    @Configuration
    @Order(Ordered.HIGHEST_PRECEDENCE)
    protected static class SecurityConfig extends WebSecurityConfigurerAdapter {

        @Value("${cas.service.login}")
        private String casUrlLogin;

        @Value("${cas.service.logout}")
        private String casUrlLogout;

        @Value("${cas.url.prefix}")
        private String casUrlPrefix;

        @Value("${app.service.security}")
        private String casServiceUrl;

        @Value("${app.service.home}")
        private String appServiceHome;

        @Value("${app.admin.userName}")
        private String appAdminUserName;

        private final TokenAuthenticationService tokenAuthenticationService;
        private final UserService userService;

        public SecurityConfig() {
            super(false);
            this.userService = new UserService();
            this.tokenAuthenticationService = new TokenAuthenticationService("tooManySecrets", userService);
        }

        @Bean
        public Set<String> adminList() {
            Set<String> admins = new HashSet<>();
            String adminUserName = appAdminUserName;

            admins.add("admin");
            if (adminUserName != null && !adminUserName.isEmpty()) {
                admins.add(adminUserName);
            }
            return admins;
        }

        @Bean
        public ServiceProperties serviceProperties() {
            ServiceProperties sp = new ServiceProperties();
            sp.setService(casServiceUrl);
            sp.setSendRenew(false);
            return sp;
        }

        @Bean
        public CasAuthenticationProvider casAuthenticationProvider() {
            CasAuthenticationProvider casAuthenticationProvider = new CasAuthenticationProvider();
            casAuthenticationProvider.setAuthenticationUserDetailsService(customUserDetailsService());
            casAuthenticationProvider.setServiceProperties(serviceProperties());
            casAuthenticationProvider.setTicketValidator(cas20ServiceTicketValidator());
            casAuthenticationProvider.setKey("an_id_for_this_auth_provider_only");
            return casAuthenticationProvider;
        }

        @Bean
        public AuthenticationUserDetailsService<CasAssertionAuthenticationToken> customUserDetailsService() {
            return new CustomUserDetailsService(adminList());
        }

        @Bean
        public SessionAuthenticationStrategy sessionStrategy() {
            SessionAuthenticationStrategy sessionStrategy = new SessionFixationProtectionStrategy();
            return sessionStrategy;
        }

        @Bean
        public Cas20ServiceTicketValidator cas20ServiceTicketValidator() {
            return new Cas20ServiceTicketValidator(casUrlPrefix);
        }

        @Bean
        public CasAuthenticationFilter casAuthenticationFilter() throws Exception {
            CasAuthenticationFilter casAuthenticationFilter = new CasAuthenticationFilter();
            casAuthenticationFilter.setAuthenticationManager(authenticationManager());
            casAuthenticationFilter.setSessionAuthenticationStrategy(sessionStrategy());
            casAuthenticationFilter.setAuthenticationSuccessHandler(new AuthSuccessHandler()); // DEMO
//            casAuthenticationFilter.setFilterProcessesUrl(casServiceUrl);
//            casAuthenticationFilter.setFilterProcessesUrl("localhost:8080/login/cas");
            return casAuthenticationFilter;
        }

        @Bean
        public CasAuthenticationEntryPoint casAuthenticationEntryPoint() {
            CasAuthenticationEntryPoint casAuthenticationEntryPoint = new CasAuthenticationEntryPoint();
            casAuthenticationEntryPoint.setLoginUrl(casUrlLogin);
            casAuthenticationEntryPoint.setServiceProperties(serviceProperties());
            return casAuthenticationEntryPoint;
        }

        @Bean
        public SingleSignOutFilter singleSignOutFilter() {
            SingleSignOutFilter singleSignOutFilter = new SingleSignOutFilter();
            singleSignOutFilter.setCasServerUrlPrefix(casUrlPrefix);
            return singleSignOutFilter;
        }

        @Bean
        public LogoutFilter requestCasGlobalLogoutFilter() {
            String logoutUrl = casUrlLogout + "?service=" + appServiceHome;
            LogoutFilter logoutFilter = new LogoutFilter(logoutUrl, new SecurityContextLogoutHandler());
            logoutFilter.setFilterProcessesUrl("/logout");
            logoutFilter.setFilterProcessesUrl("/j_spring_cas_security_logout");
            logoutFilter.setLogoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"));
            return logoutFilter;
        }

        @Autowired
        public void configureGlobal(AuthenticationManagerBuilder auth) throws Exception {
            auth.authenticationProvider(casAuthenticationProvider());
        }

        @Override
        public void configure(WebSecurity web) throws Exception {
            web.ignoring()
                    .antMatchers("/app/**")
                    .antMatchers("/bower_components/**")
                    .antMatchers("/content/**")
                    .antMatchers("/fonts/**")
                    .antMatchers("/images/**")
                    .antMatchers("/scripts/**")
                    .antMatchers("/styles/**")
                    .antMatchers("/views/**")
                    .antMatchers("/i18n/**");
        }

    //    @Override
    //    protected void configure(HttpSecurity http) throws Exception {
    //        http
    //                .addFilterAfter(new TokenGeneratorFilter(tokenAuthenticationService, userService), LogoutFilter.class)
    //                .exceptionHandling()
    //                .authenticationEntryPoint(casAuthenticationEntryPoint()).and()
    //                .addFilter(casAuthenticationFilter())
    //                .addFilterBefore(singleSignOutFilter(), CasAuthenticationFilter.class)
    //                .addFilterBefore(requestCasGlobalLogoutFilter(), LogoutFilter.class);
    //
    //        http
    //                .headers().frameOptions().disable().and()
    //                .authorizeRequests()
    //                .antMatchers("/", "/login", "/logout", "/api/secure", "/api/admin").authenticated()
    //                .antMatchers("/api/admin").hasAuthority(AuthoritiesConstants.ADMIN)
    //                .anyRequest().authenticated();
    //
    //        /**
    //         * <logout invalidate-session="true" delete-cookies="JSESSIONID" />
    //         */
    //        http
    //                .logout()
    //                .logoutUrl("/logout")
    //                .logoutSuccessUrl("/")
    //                .invalidateHttpSession(true)
    //                .deleteCookies("JSESSIONID");
    //
    //        // http.csrf();
    //    }

        @Override
        protected void configure(HttpSecurity http) throws Exception {
            // @formatter:off
            http
                    .addFilter(casAuthenticationFilter())
                    .exceptionHandling()
                    .authenticationEntryPoint(casAuthenticationEntryPoint()).and()

                    .addFilterBefore(singleSignOutFilter(), CasAuthenticationFilter.class)
                    .addFilterBefore(requestCasGlobalLogoutFilter(), LogoutFilter.class)

                    .logout()
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                    .and()

                    .headers().frameOptions().disable().and()
                    .authorizeRequests()
                    .antMatchers("/index.html", "/").authenticated()
                    .anyRequest().authenticated()
                    .and()
                    .csrf().disable()

//                    .csrf().csrfTokenRepository(csrfTokenRepository())
//            .and()
                    .addFilterAfter(csrfHeaderFilter(), SessionManagementFilter.class)
                    .addFilterAfter(new TokenGeneratorFilter(tokenAuthenticationService, userService), LogoutFilter.class);
            // @formatter:on
        }

        @Override
          protected void configure(AuthenticationManagerBuilder auth) throws Exception {
            auth
                .authenticationProvider(casAuthenticationProvider());
          }

        private Filter csrfHeaderFilter() {
            return new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
                    CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
                    if (csrf != null) {
                        Cookie cookie = WebUtils.getCookie(request, "XSRF-TOKEN");
                        String token = csrf.getToken();
                        if (cookie == null || token != null && !token.equals(cookie.getValue())) {
                            cookie = new Cookie("XSRF-TOKEN", token);
                            cookie.setPath("/");
                            response.addCookie(cookie);
                        }
                    }
                    filterChain.doFilter(request, response);
                }
            };
        }

        private CsrfTokenRepository csrfTokenRepository() {
            HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
            repository.setHeaderName("X-XSRF-TOKEN");
            return repository;
        }
    }

//    protected static class SecurityConfiguration extends WebSecurityConfigurerAdapter {
//
//        @Autowired
//        public void globalUserDetails(AuthenticationManagerBuilder auth) throws Exception {
//            // @formatter:off
//            auth.inMemoryAuthentication()
//                    .withUser("user").password("password").roles("USER")
//                    .and()
//                    .withUser("admin").password("admin").roles("USER", "ADMIN", "READER", "WRITER")
//                    .and()
//                    .withUser("audit").password("audit").roles("USER", "ADMIN", "READER");
//            // @formatter:on
//        }
//
//        @Override
//        protected void configure(HttpSecurity http) throws Exception {
//            // @formatter:off
//            http
//                    .httpBasic()
//                    .and()
//                    .logout()
//                    .and()
//                    .authorizeRequests()
//                    .antMatchers("/index.html", "/login", "/").permitAll()
//                    .anyRequest().authenticated()
//                    .and()
//                    .csrf().csrfTokenRepository(csrfTokenRepository())
//                    .and()
//                    .addFilterAfter(csrfHeaderFilter(), SessionManagementFilter.class);
//            // @formatter:on
//        }
//
//        private Filter csrfHeaderFilter() {
//            return new OncePerRequestFilter() {
//                @Override
//                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
//                    CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
//                    if (csrf != null) {
//                        Cookie cookie = WebUtils.getCookie(request, "XSRF-TOKEN");
//                        String token = csrf.getToken();
//                        if (cookie == null || token != null && !token.equals(cookie.getValue())) {
//                            cookie = new Cookie("XSRF-TOKEN", token);
//                            cookie.setPath("/");
//                            response.addCookie(cookie);
//                        }
//                    }
//                    filterChain.doFilter(request, response);
//                }
//            };
//        }
//
//        private CsrfTokenRepository csrfTokenRepository() {
//            HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
//            repository.setHeaderName("X-XSRF-TOKEN");
//            return repository;
//        }
//    }

}
