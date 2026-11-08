<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=true; section>
<!-- template: vk-email-code.ftl -->
    <#if section = "header">
        ${msg("vkEmailCodeTitle")}
    <#elseif section = "form">
        <form id="kc-vk-email-code-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
            <p>${msg("vkEmailCodeInfo", address)}</p>
            <@field.input name="code" label=msg("vkEmailCodeLabel") autocomplete="one-time-code" autofocus=true />
            <@buttons.actionGroup>
                <@buttons.button id="kc-vk-email-confirm" name="verify" label="vkEmailConfirm" />
            </@buttons.actionGroup>
            <@buttons.actionGroup horizontal=true>
                <@buttons.button id="kc-vk-email-resend" name="resend" label="vkEmailResend" type="secondary" />
                <@buttons.button id="kc-vk-email-change" name="change" label="vkEmailChange" type="secondary" />
            </@buttons.actionGroup>
        </form>
    </#if>
</@layout.registrationLayout>
