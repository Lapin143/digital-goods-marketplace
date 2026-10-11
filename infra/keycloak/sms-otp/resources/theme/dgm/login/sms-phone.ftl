<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=true; section>
<!-- template: sms-phone.ftl -->
    <#if section = "header">
        ${msg("smsPhoneTitle")}
    <#elseif section = "form">
        <form id="kc-sms-phone-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <p>${msg("smsPhoneHint")}</p>
            <@field.input name="phone" label=msg("smsPhoneLabel") autocomplete="tel" autofocus=true />
            <@buttons.actionGroup>
                <@buttons.button id="kc-sms-send" name="send" label="smsSend" />
            </@buttons.actionGroup>
        </form>
    </#if>
</@layout.registrationLayout>
