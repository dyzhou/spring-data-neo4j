package org.springframework.data.neo4j.examples.galaxy;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.neo4j.ogm.testutil.MultiDriverTestClass;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.neo4j.examples.galaxy.context.GalaxyContext;
import org.springframework.data.neo4j.examples.galaxy.domain.World;
import org.springframework.data.neo4j.examples.galaxy.repo.WorldRepository;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;

@ContextConfiguration(classes = {GalaxyContext.class})
@RunWith(SpringJUnit4ClassRunner.class)
public class WorldRepositoryIT extends MultiDriverTestClass {

	@Autowired
	WorldRepository worldRepository;

	@Autowired
	TransactionTemplate transactionTemplate;

	boolean failed = false;

	@Test
	public void simpleWithOneThreadWorks() throws Exception {
		transactionTemplate.execute(new TransactionCallbackWithoutResult() {
			@Override
			protected void doInTransactionWithoutResult(TransactionStatus status) {
				World world1 = new World("world 1", 1);
				worldRepository.save(world1, 0);

				World world2 = new World("world 2", 2);
				worldRepository.save(world2, 0);
			}
		});

		int iterations = 1000;

		for (int i = 0; i < iterations; i++) {

			World world = worldRepository.findByName("world 1");
			assertEquals("world 1", world.getName());

			world = worldRepository.findByName("world 2");
			assertEquals("world 2", world.getName());
		}
	}

	@Test
	public void multipleThreadsResultsGetMixedUp() throws Exception {

			World world1 = new World("world 1", 1);
			worldRepository.save(world1, 0);

			World world2 = new World("world 2", 2);
			worldRepository.save(world2, 0);

		int iterations = 1000;

		ExecutorService service = Executors.newFixedThreadPool(2);
		final CountDownLatch countDownLatch = new CountDownLatch(iterations * 2);

		for (int i = 0; i < iterations; i++) {

			service.execute(() -> {
				World world = worldRepository.findByName("world 1");

				if (!"world 1".equals(world.getName())) {
					System.out.println("=================================");
					System.out.println("World 1 did not equal");
					System.out.println("world is: " + world);
					System.out.println("=================================");
					failed = true;
				}
				countDownLatch.countDown();
			});

			service.execute(() -> {

				World world = worldRepository.findByName("world 2");

				if (!"world 2".equals(world.getName())) {
					System.out.println("=================================");
					System.out.println("World 2 did not equal");
					System.out.println("world is: " + world);
					System.out.println("=================================");
					failed = true;
				}
				countDownLatch.countDown();
			});
		}
		countDownLatch.await();
		assertFalse(failed);
	}
}
