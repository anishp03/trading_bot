package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

public class FuturesDynamicRiskPolicyTest {
	@Test
	public void staticPolicyKeepsConfiguredRiskBudget() throws Exception {
		Object policy = dynamicRiskPolicy("STATIC_WITHDRAW_DAILY");
		Object budget = computeRiskBudget(policy, 700.0, 1000.0, 2000.0, 2000.0);

		assertEquals(700.0, doubleField(budget, "availableRiskBudget"), 0.001);
		assertEquals(700.0, doubleField(budget, "dynamicMaxRisk"), 0.001);
	}

	@Test
	public void dynamicPolicyUsesDllMllCapsAndReserve() throws Exception {
		Object policy = dynamicRiskPolicy("DYNAMIC_COMPOUND_MLL");
		Object budget = computeRiskBudget(policy, 700.0, 1400.0, 7000.0, 2000.0);

		assertEquals(660.0, doubleField(budget, "availableRiskBudget"), 0.001);
		assertEquals(2100.0, doubleField(budget, "dynamicMaxRisk"), 0.001);
		assertEquals(660.0, doubleField(budget, "dailyRoomBudget"), 0.001);
		assertEquals(2100.0, doubleField(budget, "mllRoomBudget"), 0.001);
	}

	@Test
	public void dynamicPolicyBlocksWhenDllRoomIsOnlyReserve() throws Exception {
		Object policy = dynamicRiskPolicy("DYNAMIC_COMPOUND_MLL");
		Object budget = computeRiskBudget(policy, 700.0, 200.0, 7000.0, 2000.0);

		assertEquals(0.0, doubleField(budget, "availableRiskBudget"), 0.001);
	}

	@Test
	public void dynamicPolicyUsesOfficialMllRoomAfterTrailingLock() throws Exception {
		Object policy = dynamicRiskPolicy("DYNAMIC_COMPOUND_MLL");
		Object budget = computeRiskBudget(policy, 700.0, 10000.0, 5000.0, 2000.0);

		assertEquals(1500.0, doubleField(budget, "availableRiskBudget"), 0.001);
		assertEquals(1750.0, doubleField(budget, "dynamicMaxRisk"), 0.001);
		assertEquals(1500.0, doubleField(budget, "mllRoomBudget"), 0.001);
	}

	@Test
	public void liveTrailingDrawdownFloorLocksAtStartingBalance() throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(
			"liveTrailingDrawdownFloor",
			double.class,
			double.class,
			double.class
		);
		method.setAccessible(true);

		assertEquals(49000.0, ((Double) method.invoke(null, 50000.0, 51000.0, 2000.0)).doubleValue(), 0.001);
		assertEquals(50000.0, ((Double) method.invoke(null, 50000.0, 55000.0, 2000.0)).doubleValue(), 0.001);
	}

	private static Object dynamicRiskPolicy(String mode) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod("dynamicRiskPolicyForMode", String.class);
		method.setAccessible(true);
		return method.invoke(null, mode);
	}

	private static Object computeRiskBudget(Object policy, double configuredMaxRisk, double dllRoom, double mllRoom, double configuredMllLimit) throws Exception {
		Method method = FuturesManager.class.getDeclaredMethod(
			"dynamicRiskBudget",
			policy.getClass(),
			double.class,
			double.class,
			double.class,
			double.class
		);
		method.setAccessible(true);
		return method.invoke(null, policy, configuredMaxRisk, dllRoom, mllRoom, configuredMllLimit);
	}

	private static double doubleField(Object target, String name) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return ((Double) field.get(target)).doubleValue();
	}
}
