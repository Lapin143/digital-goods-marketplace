<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=messagesPerField.exists('global'); section>
<!-- template: register-neutral.ftl -->
    <#if section = "header">
        ${msg("registerTitle")}
    <#elseif section = "form">
        <form id="kc-register-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
            <@field.input name="email" label=msg("email") value=(email!'') required=true autocomplete="email" autofocus=true />
            <@field.password name="password" label=msg("password") required=true autocomplete="new-password" />
            <@field.password name="password-confirm" label=msg("passwordConfirm") required=true autocomplete="new-password" />
            <@buttons.actionGroup>
                <@buttons.button id="kc-register" name="register" label="doRegister" />
            </@buttons.actionGroup>
            <div class="${properties.kcFormGroupClass!} pf-v5-c-login__main-footer-band">
                <div id="kc-form-options" class="${properties.kcFormOptionsClass!} pf-v5-c-login__main-footer-band-item">
                    <div class="${properties.kcFormOptionsWrapperClass!}">
                        <span><a href="${url.loginUrl}">${msg("backToLogin")}</a></span>
                    </div>
                </div>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
