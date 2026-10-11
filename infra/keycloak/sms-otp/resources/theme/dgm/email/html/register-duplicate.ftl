<#import "template.ftl" as layout>
<@layout.emailLayout>
${kcSanitize(msg("registerDuplicateBodyHtml", loginLink, realmName))?no_esc}
</@layout.emailLayout>
