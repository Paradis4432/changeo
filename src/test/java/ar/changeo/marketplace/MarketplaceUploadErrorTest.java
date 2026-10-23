package ar.changeo.marketplace;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MarketplaceUploadErrorTest {
    private final MarketplaceUploadErrors resolver=new MarketplaceUploadErrors();

    @ParameterizedTest @ValueSource(strings={"/marketplace/drafts","/marketplace/back","/marketplace/prepare"})
    void shouldPresentOnlySafeUploadGuidanceWithoutReadingRejectedBody(String route) {
        var request=mock(HttpServletRequest.class);
        UUID stage=UUID.randomUUID();
        when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn(route);
        when(request.getQueryString()).thenReturn("recovery="+stage);
        var result=resolver.resolveErrorView(request,HttpStatus.CONTENT_TOO_LARGE,Map.of("message","private injected error","exception","stack"));
        assertThat(result.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(result.getViewName()).isEqualTo("marketplace-error");
        assertThat(result.getModel().get("message")).asString().contains("cuatro archivos de 1 MiB","hasta 5 MiB","No pudimos leer").doesNotContain("injected","stack");
        if (route.equals("/marketplace/prepare")) { assertThat(result.getModel()).doesNotContainKey("recoveryStage"); }
        else { assertThat(result.getModel().get("recoveryStage")).isEqualTo(stage.toString()); }
        verify(request).getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        verify(request).getQueryString();
        verifyNoMoreInteractions(request);
    }

    @ParameterizedTest @NullSource @ValueSource(strings={"", "recovery=1-1-1-1-1", "recovery=https://invalid.example", "recovery=00000000-0000-0000-0000-000000000000&recovery=00000000-0000-0000-0000-000000000000", "recovery=%30%30%30%30%30%30%30%30-0000-0000-0000-000000000000", "recovery=FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF", "recovery=00000000-0000-0000-0000-000000000000&next=https://invalid.example", "recovery=00000000-0000-0000-0000-000000000000#fragment"})
    void shouldOmitMalformedDuplicateEncodedOrExternalCarriers(String query) {
        var request=mock(HttpServletRequest.class);
        when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn("/marketplace/drafts");
        when(request.getQueryString()).thenReturn(query);
        assertThat(resolver.resolveErrorView(request,HttpStatus.CONTENT_TOO_LARGE,Map.of()).getModel()).doesNotContainKey("recoveryStage");
        verify(request).getAttribute(RequestDispatcher.ERROR_REQUEST_URI); verify(request).getQueryString(); verifyNoMoreInteractions(request);
    }

    @ParameterizedTest @ValueSource(strings={"/marketplace/compose","/marketplace/drafts/","https://invalid.example/marketplace/drafts","/conversations/messages","/marketplace/drafts?recovery=bad"})
    void shouldLeaveOtherErrorRoutesUnchanged(String route) {
        var request=mock(HttpServletRequest.class);
        when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn(route);
        assertThat(resolver.resolveErrorView(request,HttpStatus.CONTENT_TOO_LARGE,Map.of())).isNull();
        verify(request).getAttribute(RequestDispatcher.ERROR_REQUEST_URI); verifyNoMoreInteractions(request);
    }

    @ParameterizedTest @ValueSource(ints={400,403,404,500,503})
    void shouldLeaveOtherStatusesUnchanged(int status) {
        var request=mock(HttpServletRequest.class);
        assertThat(resolver.resolveErrorView(request,HttpStatus.valueOf(status),Map.of())).isNull();
        verify(request).getAttribute(RequestDispatcher.ERROR_REQUEST_URI); verifyNoMoreInteractions(request);
    }
}
