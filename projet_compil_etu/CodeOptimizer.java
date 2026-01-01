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

        // Calcul des LVentry et des LVexit
        computeLiveness();

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

    /**
     * Calcule les LVentry et les LVexit de chaque bloc
     *
     */
    private void computeLiveness(){
        computeGenKill();

        boolean changed = true;
        while(changed){
            changed = false;

            // Parcours à l'envers pour optimiser le nombre d'itérations
            for(int i = blocks.size() - 1; i >= 0; i--){
                InstructionBlock block = blocks.get(i);

                List<InstructionBlock> neighbors = controlGraph.getOutNeighbors(block);

                // Calcul du LVexit avec la formule : LVexit(n) = Union(LVentry(s)) pour tous les successeurs s de n
                Set<Integer> lvExit = new HashSet<>();
                for(InstructionBlock neighbor : neighbors){
                    lvExit.addAll(neighbor.lvEntry);
                }

                // Calcul du LVentry avec la formule : LVentry(n) = Gen(n) U (LVexit(n) - Kill(n))
                Set<Integer> lvEntry = new HashSet<>();
                for(InstructionBlock neighbor : neighbors){
                    lvEntry.addAll(neighbor.gen);

                    for(Integer exit : lvExit){
                        if(!block.kill.contains(exit)){
                            lvEntry.add(exit);
                        }
                    }
                }

                // Vérifie si le bloc a été modifié (si c'est le cas pour un bloc, on recalculera pour chaque bloc)
                if(!block.lvExit.equals(lvExit) || !block.lvEntry.equals(lvEntry)){
                    block.lvExit.addAll(lvExit);
                    block.lvEntry.addAll(lvEntry);
                    changed = true;
                }
            }
        }
    }

    /**
     * Calcule les variables générées et tuées de chaque bloc
     *
     */
    private void computeGenKill(){
        for(InstructionBlock block : blocks){
            for(Instruction  instruction : block.instructions){
                List<Integer> reads = getReadRegisters(instruction);
                Integer write = getWrittenRegister(instruction);

                for(Integer reg : reads){
                    if(block.kill.contains(reg)){
                        block.gen.add(reg);
                    }
                }

                if(write != null){
                    block.kill.add(write);
                }
            }
        }
    }

    /**
     * Permet de récupérer les registres lus dans une instruction
     *
     * @param instruction           L'instruction dans laquelle chercher les registres
     * @return                      Les registres lus
     */
    private List<Integer> getReadRegisters(Instruction instruction){
        List<Integer> readRegisters = new ArrayList<>();

        // Cas 1 : L'instruction est une instruction UAL
        if(instruction instanceof UAL ual){
            readRegisters.add(ual.getSr1());
            readRegisters.add(ual.getSr2());
        }

        // Cas 2 : L'instruction est une instruction UAL immédiate
        else if(instruction instanceof UALi uali){
            readRegisters.add(uali.getSr());
        }

        // Cas 3 : L'instruction est un saut conditionnel
        else if(instruction instanceof CondJump condJump){
            readRegisters.add(condJump.getSr1());
            readRegisters.add(condJump.getSr2());
        }

        // Cas 4 : L'instruction est une instruction mémoire
        else if(instruction instanceof Mem mem){

            // Sous-cas 1 : L'instruction est un LD
            if(mem.getName().equals(Mem.Op.LD.toString())){
                readRegisters.add(mem.getAddress());
            }

            // Sous-cas 2 : L'instruction est un ST
            else{
                readRegisters.add(mem.getDest());
                readRegisters.add(mem.getAddress());
            }
        }

        // Cas 5 : L'instruction est une instruction d'entrée-sortie
        else if(instruction instanceof IO io){

            // Sous-cas 1 : L'instruction est un OUT ou un PRINT
            if(io.getName().equals(IO.Op.OUT.toString()) || io.getName().equals(IO.Op.PRINT.toString())){
                readRegisters.add(io.getReg());
            }

            // Sous-cas 2 : L'instruction est un IN ou un READ (ne rien faire)
        }

        return readRegisters;
    }

    /**
     * Permet de récupérer le registre écrit dans une instruction
     *
     * @param instruction           L'instruction dans laquelle chercher le registre
     * @return                      Le registre écrit
     */
    private Integer getWrittenRegister(Instruction instruction){
        // Cas 1 : L'instruction est une instruction UAL
        if(instruction instanceof UAL ual){
            return ual.getDest();
        }

        // Cas 2 : L'instruction est une instruction UAL immédiate
        else if(instruction instanceof UALi uali){
            return uali.getDest();
        }

        // Cas 3 : L'instruction est une instruction mémoire
        else if(instruction instanceof Mem mem){

            // Sous-cas 1 : L'instruction est un LD
            if(mem.getName().equals(Mem.Op.LD.toString())){
                return mem.getDest();
            }

            // Sous-cas 2 : L'instruction est un ST (ne rien faire)
        }

        // Cas 4 : L'instruction est une instruction d'entrée-sortie
        else if(instruction instanceof IO io){

            // Sous-cas 1 : L'instruction est un IN ou un READ
            if(io.getName().equals(IO.Op.IN.toString()) || io.getName().equals(IO.Op.READ.toString())){
                return io.getReg();
            }

            // Sous-cas 2 : L'instruction est un OUT ou un PRINT (ne rien faire)
        }

        return null;
    }


}
