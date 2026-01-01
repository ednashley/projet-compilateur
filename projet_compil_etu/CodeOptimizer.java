import Asm.*;
import Graph.OrientedGraph;
import Graph.UnorientedGraph;

import java.util.*;

// Classe publique de l'optimiseur de code assembleur pour ne pas dépasser le nombre de registres de la machine
public class CodeOptimizer {
    private int nRegs;

    private List<InstructionBlock> blocks;

    private OrientedGraph<InstructionBlock> controlGraph;
    private UnorientedGraph<Integer> conflictGraph;

    private Program program;

    // Classe privée pour chaque bloc d'instructions
    private static class InstructionBlock{
        private static int nbBlocks = 0;
        private final int id;

        private ArrayList<Instruction> instructions = new ArrayList<>();

        private Set<Integer> gen = new HashSet<>();
        private Set<Integer> kill = new HashSet<>();
        private Set<Integer> lvEntry = new HashSet<>();
        private Set<Integer> lvExit = new HashSet<>();

        /**
         * Constructeur du bloc d'instructions
         *
         */
        public InstructionBlock(){
            this.id = nbBlocks;
            nbBlocks += 1;
        }

        /**
         * Ajoute une instruction au bloc
         *
         * @param instruction               Instruction à ajouter
         * @throws NullPointerException     Si l'instruction est null
         */
        public void addInstruction(Instruction instruction) throws NullPointerException{
            if(instruction != null){
                instructions.add(instruction);
            } else{
                throw new NullPointerException();
            }
        }
    }

    /**
     * Constructeur de l'optimiseur
     *
     * @param numberOfRegs      Nombre de registres de la machine
     */
    public CodeOptimizer(int numberOfRegs){
        this.nRegs = numberOfRegs;

        this.conflictGraph = new UnorientedGraph<Integer>();
    }

    /**
     * Optimise le code du programme fourni pour n'utiliser qu'un nombre maximal de registres
     *
     * @param program       Programme à optimiser
     * @return              Programme optimisé
     */
    public Program optimize(Program program){
        this.program = program;

        // Construction du graphe de contrôle
        buildControlGraph();

        for(InstructionBlock block : blocks){
            System.out.println("Bloc " + block.id + " : " + block.instructions);
            System.out.print("Voisins : ");
            for(InstructionBlock neighbor : controlGraph.getOutNeighbors(block)){
                System.out.print(neighbor.id + " ");
            }
            System.out.println();
        }

        return this.program;
    }

    /**
     * Construis le graphe de contrôle
     *
     */
    private void buildControlGraph(){
        this.controlGraph = new OrientedGraph<InstructionBlock>();

        // On crée des blocs d'instructions
        createBlocks();

        // On ajoute chaque bloc en tant que sommet du graphe de contrôle
        for (InstructionBlock block : blocks) {
            controlGraph.addVertex(block);
        }

        // On ajoute les arêtes du graphe de contrôle
        linkBlocks();
    }

    /**
     * Créer les blocs d'instructions du programme assembleur
     *
     */
    private void createBlocks(){
        this.blocks = new ArrayList<>();

        ArrayList<Instruction> instructions = program.getInstructions();

        InstructionBlock block = new InstructionBlock();
        block.addInstruction(instructions.getFirst());
        for(int i = 1; i < instructions.size(); i++){
            if(instructions.get(i-1) instanceof JumpCall || instructions.get(i-1) instanceof CondJump || !instructions.get(i).getLabel().isEmpty()){
                blocks.add(block);
                block = new InstructionBlock();
                block.addInstruction(instructions.get(i));
            }
            else{
                block.addInstruction(instructions.get(i));
            }
        }
        blocks.add(block);
    }

    /**
     * Ajoute les arêtes liant les blocs se succédant entre eux au graphe de contrôle
     *
     */
    private void linkBlocks(){
        Map<String, InstructionBlock> labelToBlock = new HashMap<>();

        // ÉTAPE 1 : On associe chaque bloc au label de la première instruction s'il en a un
        for(InstructionBlock block : blocks){
            if(!block.instructions.isEmpty()){
                String label = block.instructions.getFirst().getLabel();
                if(label != null && !label.isEmpty()){
                    labelToBlock.put(label, block);
                }
            }
        }

        // ÉTAPE 2 : On analyse la dernière instruction de chaque bloc afin de déterminer quelles peuvent être les instructions suivantes
        for(int i = 0; i < blocks.size(); i++){
            InstructionBlock block = blocks.get(i);
            Instruction lastInstruction = block.instructions.getLast();

            // Cas 1 : C'est un saut simple
            if(lastInstruction instanceof JumpCall && labelToBlock.containsKey(((JumpCall) lastInstruction).getAddress())){

                // Sous-cas 1 : L'instruction est un CALL, on ajoute alors l'instruction suivante
                if(lastInstruction.getName().equals(JumpCall.Op.CALL.toString())){
                    if(i < blocks.size() - 1){
                        controlGraph.addEdge(block, blocks.get(block.id + 1));
                    }
                }

                // Sous-cas 2 : L'instruction est un JMP, on ajoute alors l'instruction correspondant au label fourni
                else {
                    controlGraph.addEdge(block, labelToBlock.get(((JumpCall) lastInstruction).getAddress()));
                }
            }

            // Cas 2 : C'est un saut conditionnel
            else if(lastInstruction instanceof CondJump && labelToBlock.containsKey(((CondJump) lastInstruction).getAddress())){
                controlGraph.addEdge(block, labelToBlock.get(((CondJump) lastInstruction).getAddress()));

                if(i < blocks.size() - 1){
                    controlGraph.addEdge(block, blocks.get(block.id + 1));
                }
            }

            // Cas 3 : Ce n'est pas une instruction de saut
            else if(!(lastInstruction instanceof Ret) && !(lastInstruction instanceof Stop)){
                if(i < blocks.size() - 1){
                    controlGraph.addEdge(block, blocks.get(block.id + 1));
                }
            }

            // Cas 4 : C'est une instruction d'arrêt (ne rien faire)
        }
    }
}
