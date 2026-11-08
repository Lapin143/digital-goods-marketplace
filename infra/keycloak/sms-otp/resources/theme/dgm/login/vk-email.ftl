<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<@layout.registrationLayout displayMessage=true; section>
<!-- template: vk-email.ftl -->
    <#if section = "header">
        ${msg("vkEmailTitle")}
    <#elseif section = "form">
        <form id="kc-vk-email-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post" novalidate="novalidate">
            <p>${msg("vkEmailHint")}</p>
            <@field.input name="email" label=msg("vkEmailLabel") autocomplete="email" autofocus=true />
            <@buttons.actionGroup>
                <@buttons.button id="kc-vk-email-send" name="send" label="vkEmailSend" />
            </@buttons.actionGroup>
        </form>
    </#if>
</@layout.registrationLayout>
