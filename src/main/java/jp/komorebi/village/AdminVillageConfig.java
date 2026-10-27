package jp.komorebi.village;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 管理人室（/admin/**）に、えらんでいる村をセットする係をつける */
@Configuration
public class AdminVillageConfig implements WebMvcConfigurer {

    private final AdminVillageInterceptor interceptor;

    public AdminVillageConfig(AdminVillageInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/admin", "/admin/**").excludePathPatterns("/admin/login", "/admin.css");
    }
}
