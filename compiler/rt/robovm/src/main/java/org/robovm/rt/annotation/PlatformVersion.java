
package org.robovm.rt.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.robovm.rt.Platform;

@Retention(RetentionPolicy.CLASS)
@Target({ElementType.ANNOTATION_TYPE})
public @interface PlatformVersion {
	Platform platform();

	String minVersion() default "";

	String maxVersion() default "";
}
