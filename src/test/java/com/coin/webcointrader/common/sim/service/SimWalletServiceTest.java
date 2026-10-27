package com.coin.webcointrader.common.sim.service;

import com.coin.webcointrader.common.account.dto.WalletBalanceDTO;
import com.coin.webcointrader.common.login.entity.User;
import com.coin.webcointrader.common.sim.entity.SimWallet;
import com.coin.webcointrader.common.sim.repository.SimWalletRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

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

    @Test
    @DisplayName("getWalletBalance: 가상 지갑 잔고를 총 자산·지갑 잔고·주문 가능 잔고에 동일하게 담고 단위는 USDT로 반환한다")
    void getWalletBalance_returnsNeutralBalance() {
        SimWallet wallet = new SimWallet();
        wallet.setBalance(new BigDecimal("10500.50000000"));
        given(simWalletRepository.findByUserId(1L)).willReturn(Optional.of(wallet));

        WalletBalanceDTO result = simWalletService.getWalletBalance(1L);

        assertThat(result.getTotalEquity()).isEqualTo("10500.5");
        assertThat(result.getWalletBalance()).isEqualTo("10500.5");
        assertThat(result.getAvailableBalance()).isEqualTo("10500.5");
        assertThat(result.getCurrency()).isEqualTo("USDT");
    }
}
