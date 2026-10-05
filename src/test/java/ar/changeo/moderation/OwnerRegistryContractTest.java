package ar.changeo.moderation;

import ar.changeo.moderation.api.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnerRegistryContractTest {
    @Test void shouldRecognizeExactlyOneOwnerWithoutGrantingAuthorization() {
        var reference=new ContentReference(SurfaceKind.REQUEST_BODY,UUID.randomUUID(),UUID.randomUUID());
        var first=mock(ContentOwner.class); var second=mock(ContentOwner.class);
        when(first.kinds()).thenReturn(Set.of(SurfaceKind.REQUEST_BODY)); when(second.kinds()).thenReturn(Set.of(SurfaceKind.REQUEST_BODY));
        when(second.owns(reference)).thenReturn(true);
        var registry=new OwnerRegistry(List.of(first,second));
        assertThat(registry.require(reference)).isSameAs(second);
        verify(first,never()).lockRevision(any(),any()); verify(second,never()).lockRevision(any(),any());
        assertThatThrownBy(() -> registry.require(new ContentReference(reference.ownerKind(),reference.resourceId(),UUID.randomUUID()))).isInstanceOf(ModerationFailure.class);
        when(first.owns(reference)).thenReturn(true); assertThatThrownBy(() -> registry.require(reference)).isInstanceOf(ModerationFailure.class);
    }
}
