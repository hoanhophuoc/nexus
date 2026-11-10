package com.haichutieu.chatsystem.server.groups;

import com.haichutieu.chatsystem.server.identity.UserNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KeyPackageServiceTest {
    private final KeyPackageRepository repository = mock(KeyPackageRepository.class);
    private final KeyPackageService keyPackages = new KeyPackageService(repository);
    private final UUID userId = UUID.randomUUID();

    @Test
    void rejectsEmptyAndOversizedPackagesBeforeStorage() {
        assertThatThrownBy(() -> keyPackages.publish(userId, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> keyPackages.publish(userId, new byte[KeyPackageService.MAX_KEY_PACKAGE_BYTES + 1]))
                .isInstanceOf(MlsPayloadTooLargeException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void storesAndFindsAPublishedPackage() {
        byte[] packageBytes = {1, 2, 3, 4};
        when(repository.find(userId)).thenReturn(Optional.of(packageBytes));

        keyPackages.publish(userId, packageBytes);

        verify(repository).store(userId, packageBytes);
        assertThat(keyPackages.find(userId)).isEqualTo(packageBytes);
    }

    @Test
    void reportsAMissingPackageAsNotFound() {
        when(repository.find(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> keyPackages.find(userId)).isInstanceOf(UserNotFoundException.class);
    }
}
