<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=true; section>
<!-- template: sms-code.ftl -->
    <#if section = "header">
        ${msg("smsCodeTitle")}
    <#elseif section = "form">
        <form id="kc-sms-code-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <p>${msg("smsCodeInfo", phoneMasked)}</p>
            <@field.input name="code" label=msg("smsCodeLabel") autocomplete="one-time-code" autofocus=true />
            <@buttons.actionGroup>
                <@buttons.button id="kc-sms-confirm" name="verify" label="smsConfirm" />
            </@buttons.actionGroup>
            <@buttons.actionGroup horizontal=true>
                <@buttons.button id="kc-sms-resend" name="resend" label="smsResend" type="secondary" />
                <@buttons.button id="kc-sms-change" name="change" label="smsChangeNumber" type="secondary" />
            </@buttons.actionGroup>
        </form>
    </#if>
</@layout.registrationLayout>
