/**
 * Precompiled [instapay.spring-boot-conventions.gradle.kts][Instapay_spring_boot_conventions_gradle] script plugin.
 *
 * @see Instapay_spring_boot_conventions_gradle
 */
public
class Instapay_springBootConventionsPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Instapay_spring_boot_conventions_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
