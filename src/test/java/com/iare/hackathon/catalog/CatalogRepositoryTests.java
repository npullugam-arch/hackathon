package com.iare.hackathon.catalog;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class CatalogRepositoryTests {
    static EmbeddedPostgres database;
    static JdbcTemplate jdbc;
    static CatalogRepository repository;
    static CatalogService service;
    @BeforeAll static void start() throws Exception {
        database=EmbeddedPostgres.builder().setPort(0).start();
        Flyway.configure().dataSource(database.getPostgresDatabase()).schemas("app_private").defaultSchema("app_private")
                .locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(database.getPostgresDatabase());repository=new CatalogRepository(jdbc);
        var beans=new StaticListableBeanFactory();beans.addBean("catalog",repository);service=new CatalogService(beans.getBeanProvider(CatalogRepository.class));
    }
    @AfterAll static void stop() throws Exception {if(database!=null)database.close();}
    @BeforeEach void clear(){jdbc.update("DELETE FROM public.products");jdbc.update("DELETE FROM public.advertisements");}
    ProductInput product(String title,boolean active){return new ProductInput(title,"https://example.com/product.jpg",new BigDecimal("1000.00"),new BigDecimal("800.00"),30,new BigDecimal("12.25"),"Full details",active);}
    @Test void productCrudPersistsComputedValuesAndPreservesCreationDate(){
        UUID id=UUID.randomUUID();var original=service.saveProduct(id,product("First",true),true);
        assertEquals(new BigDecimal("367.50"),original.totalEarnings());assertFalse(original.soldOut());
        assertEquals(1,service.products(true).size());
        var changed=service.saveProduct(id,product("Updated",false),false);assertEquals(original.createdAt(),changed.createdAt());
        assertEquals("Updated",new CatalogRepository(jdbc).product(id,false).orElseThrow().title());
        assertTrue(service.products(true).isEmpty());assertThrows(ResponseStatusException.class,()->service.product(id,true));
        service.deleteProduct(id);assertTrue(service.products(false).isEmpty());
    }
    @Test void activeAdIsExclusiveAndChangingItChangesTheDismissalRevision(){
        UUID firstId=UUID.randomUUID(), secondId=UUID.randomUUID();
        var first=service.saveAdvertisement(firstId,new AdvertisementInput("First","https://example.com/first.jpg",true),true);
        service.saveAdvertisement(secondId,new AdvertisementInput("Second","https://example.com/second.jpg",true),true);
        assertEquals(secondId,service.advertisements(true).get(0).id());assertEquals(1,service.advertisements(true).size());
        var reactivated=service.saveAdvertisement(firstId,new AdvertisementInput("Updated","https://example.com/new.jpg",true),false);
        assertTrue(reactivated.updatedAt().isAfter(first.updatedAt()));
        service.saveAdvertisement(firstId,new AdvertisementInput("Updated","https://example.com/new.jpg",false),false);assertTrue(service.advertisements(true).isEmpty());
        service.deleteAdvertisement(firstId);assertEquals(1,service.advertisements(false).size());
    }
    @Test void concurrentActivationLeavesExactlyOneActiveAdvertisement() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {var tasks=new ArrayList<Callable<Advertisement>>();for(int i=0;i<8;i++)tasks.add(()->service.saveAdvertisement(UUID.randomUUID(),new AdvertisementInput("Concurrent","https://example.com/ad.jpg",true),true));
            for(var result:pool.invokeAll(tasks))assertNotNull(result.get());assertEquals(1,service.advertisements(true).size());
        }finally{pool.shutdownNow();}
    }
    @Test void nonexistentAdUpdateRollsBackDeactivationOfTheCurrentAd(){
        UUID id=UUID.randomUUID();var input=new AdvertisementInput("Keep active","https://example.com/ad.jpg",true);service.saveAdvertisement(id,input,true);
        assertThrows(ResponseStatusException.class,()->service.saveAdvertisement(UUID.randomUUID(),input,false));
        assertEquals(id,service.advertisements(true).get(0).id());
    }
    @Test void insecureImageAndInvalidPricingAreRejected(){
        assertThrows(ResponseStatusException.class,()->service.saveAdvertisement(UUID.randomUUID(),new AdvertisementInput("Bad","javascript:alert(1)",true),true));
        var p=product("Bad price",true);
        assertThrows(ResponseStatusException.class,()->service.saveProduct(UUID.randomUUID(),new ProductInput(p.title(),p.imageUrl(),p.originalPrice(),new BigDecimal("2000"),p.totalClaims(),p.dailyIncome(),p.description(),true),true));
        assertTrue(service.products(false).isEmpty());
    }

    @Test void machineSeedsAndAdminChangesPersistAndInactiveMachinesAreHidden(){
        assertEquals(5,service.machines(true).size());
        UUID id=UUID.randomUUID();var input=new MachineInput("Test machine","/assets/machine-1.svg","Preview","Summary","Full details",true);
        var created=service.saveMachine(id,input,true);assertEquals(6,service.machines(true).size());
        var changed=service.saveMachine(id,new MachineInput("Updated","https://example.com/machine.jpg","New title","New summary","New details",false),false);
        assertEquals(created.createdAt(),changed.createdAt());assertEquals("Updated",new CatalogRepository(jdbc).machine(id,false).orElseThrow().name());
        assertThrows(ResponseStatusException.class,()->service.machine(id,true));assertEquals(5,service.machines(true).size());
        service.deleteMachine(id);assertTrue(repository.machine(id,false).isEmpty());
        assertThrows(ResponseStatusException.class,()->service.saveMachine(UUID.randomUUID(),new MachineInput("Bad","javascript:alert(1)","Title","Summary","Details",true),true));
    }
}
