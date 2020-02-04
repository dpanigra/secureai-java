package com.secureai;

import com.secureai.model.actionset.ActionSet;
import com.secureai.model.topology.Topology;
import com.secureai.nn.FilteredMultiLayerNetwork;
import com.secureai.nn.NNBuilder;
import com.secureai.system.SystemEnvironment;
import com.secureai.system.SystemState;
import com.secureai.utils.ArgsUtils;
import com.secureai.utils.RLStatTrainingListener;
import com.secureai.utils.YAML;
import org.apache.log4j.BasicConfigurator;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.rl4j.learning.sync.qlearning.QLearning;
import org.deeplearning4j.rl4j.learning.sync.qlearning.discrete.QLearningDiscreteDense;
import org.deeplearning4j.rl4j.network.dqn.DQN;
import org.deeplearning4j.rl4j.util.DataManager;
import org.deeplearning4j.rl4j.util.DataManagerTrainingListener;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

public class DQNMain {

    public static void main(String... args) throws IOException {
        BasicConfigurator.configure();
        Map<String, String> argsMap = ArgsUtils.toMap(args);

        Topology topology = YAML.parse(String.format("data/topologies/topology-%s.yml", argsMap.getOrDefault("topology", "paper-4")), Topology.class);
        ActionSet actionSet = YAML.parse(String.format("data/action-sets/action-set-%s.yml", argsMap.getOrDefault("actionSet", "paper")), ActionSet.class);

        QLearning.QLConfiguration qlConfiguration = new QLearning.QLConfiguration(
                Integer.parseInt(argsMap.getOrDefault("seed", "123")),                //Random seed
                Integer.parseInt(argsMap.getOrDefault("maxEpochStep", "100")),        //Max step By epoch
                Integer.parseInt(argsMap.getOrDefault("maxStep", "10000")),           //Max step
                Integer.parseInt(argsMap.getOrDefault("expRepMaxSize", "15000")),    //Max size of experience replay
                Integer.parseInt(argsMap.getOrDefault("batchSize", "128")),            //size of batches
                Integer.parseInt(argsMap.getOrDefault("targetDqnUpdateFreq", "400")), //target update (hard)
                Integer.parseInt(argsMap.getOrDefault("updateStart", "100")),          //num step noop warmup
                Double.parseDouble(argsMap.getOrDefault("rewardFactor", ".01")),       //reward scaling
                Double.parseDouble(argsMap.getOrDefault("gamma", "0.1")),            //gamma
                Double.parseDouble(argsMap.getOrDefault("errorClamp", ".8")),       //td-error clipping
                Float.parseFloat(argsMap.getOrDefault("minEpsilon", "0.1f")),         //min epsilon
                Integer.parseInt(argsMap.getOrDefault("epsilonNbStep", "1000")),      //num step for eps greedy anneal
                Boolean.parseBoolean(argsMap.getOrDefault("doubleDQN", "false"))       //double DQN
        );

        SystemEnvironment mdp = new SystemEnvironment(topology, actionSet);
        FilteredMultiLayerNetwork nn = new NNBuilder().build(mdp.getObservationSpace().size(), mdp.getActionSpace().getSize(), Integer.parseInt(argsMap.getOrDefault("layers", "1")));

        nn.setMultiLayerNetworkPredictionFilter(input -> mdp.getActionSpace().actionsMask(input));
        System.out.println(nn.summary());
        nn.setListeners(new ScoreIterationListener(100));

        QLearningDiscreteDense<SystemState> dql = new QLearningDiscreteDense<>(mdp, new DQN<>(nn), qlConfiguration);
        //QLearningDiscreteDense<SystemState> dql = new QLearningDiscreteDense<>(mdp, new ParallelDQN<>(nn), qlConfiguration);
        //QLearningDiscreteDense<SystemState> dql = new QLearningDiscreteDense<>(mdp, new SparkDQN<>(nn), qlConfiguration);
        DataManager dataManager = new DataManager(true);
        dql.addListener(new DataManagerTrainingListener(dataManager));
        dql.addListener(new RLStatTrainingListener(dataManager.getInfo().substring(0, dataManager.getInfo().lastIndexOf('/'))));
        dql.train();

        int EPISODES = 10;
        double rewards = 0;
        for (int i = 0; i < EPISODES; i++) {
            mdp.reset();
            double reward = dql.getPolicy().play(mdp);
            rewards += reward;
            Logger.getAnonymousLogger().info("[Evaluate] Reward: " + reward);
        }
        Logger.getAnonymousLogger().info("[Evaluate] Average reward: " + rewards / EPISODES);
    }
}
