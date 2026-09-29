package com.iare.hackathon.withdrawal;

import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BankDirectory {
    private final Map<String, String> names;
    public BankDirectory() throws java.io.IOException {
        try (var input = new ClassPathResource("banknames.json").getInputStream()) {
            var node = JsonMapper.builder().build().readTree(input);
            var values = new TreeMap<String, String>();
            node.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asString()));
            names = Map.copyOf(values);
        }
    }
    public List<WithdrawalDtos.Bank> banks() {
        return names.entrySet().stream().map(e -> new WithdrawalDtos.Bank(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(WithdrawalDtos.Bank::name)).toList();
    }
    public String name(String code) {
        var name = names.get(code);
        if (name == null) throw WithdrawalService.invalid("Select a bank from the bank list.");
        return name;
    }
}
