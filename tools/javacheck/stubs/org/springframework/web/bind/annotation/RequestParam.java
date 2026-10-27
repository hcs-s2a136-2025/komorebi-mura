package org.springframework.web.bind.annotation; public @interface RequestParam { String defaultValue() default ""; boolean required() default true; }
