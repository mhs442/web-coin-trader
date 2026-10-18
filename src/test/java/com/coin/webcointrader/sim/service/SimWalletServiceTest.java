package com.coin.webcointrader.sim.service;

import com.coin.webcointrader.login.entity.User;
import com.coin.webcointrader.sim.entity.SimWallet;
import com.coin.webcointrader.sim.repository.SimWalletRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class SimWalletServiceTest {

    @InjectMocks
    private SimWalletService simWalletService;

    @Mock
    private SimWalletRepository simWalletRepository;

    @Mock
    private EntityManager entityManager;

    @Test
    @DisplayName("createWallet: userId 로 사용자 참조를 얻어 기본 잔고(10,000)의 지갑을 저장한다")
    void createWallet_savesDefaultWallet() {
        User userRef = new User();
        given(entityManager.getReference(User.class, 1L)).willReturn(userRef);

        simWalletService.createWallet(1L);

        then(simWalletRepository).should().save(argThat((SimWallet w) ->
                w.getUser() == userRef && w.getBalance().compareTo(new BigDecimal("10000")) == 0));
    }
}
