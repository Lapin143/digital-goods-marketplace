<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
<!-- template: register-sent.ftl -->
    <#if section = "header">
        ${msg("registerSentTitle")}
    <#elseif section = "form">
        <div id="kc-register-sent">
            <p class="instruction">${msg("registerSentInfo")}</p>
            <p><a id="kc-back-to-login" href="${url.loginUrl}">${msg("backToLogin")}</a></p>
        </div>
    </#if>
</@layout.registrationLayout>
