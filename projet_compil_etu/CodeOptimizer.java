import Asm.*;
import Graph.OrientedGraph;
import Graph.UnorientedGraph;

import java.util.*;

// Classe publique de l'optimiseur de code assembleur pour ne pas dépasser le nombre de registres de la machine
public class CodeOptimizer {

    // Numéro du premier registre disponible
    private static final int START_REG = 3;

    // Nombre de registres utilisés
    private final int NB_REG_MAX;

    // Registres temporaires pour les échanges si besoin
    private final int TMP_REG_1;
    private final int TMP_REG_2;

    // Pointeur de pile Spill
    private final int REG_SPILL_PTR;

    // Adresse mémoire de départ pour le Spill
    private static final int START_SPILL_ADDR = 50000;

    private List<InstructionBlock> blocks;

    private OrientedGraph<InstructionBlock> controlGraph;
    private UnorientedGraph<Integer> conflictGraph;

    private int colorSize;

    private Program program;

    // Classe privée pour chaque bloc d'instructions
    private static class InstructionBlock{
        private static int nbBlocks = 0;
        private final int id;

        private final ArrayList<Instruction> instructions = new ArrayList<>();

        private final Set<Integer> gen = new HashSet<>();
        private final Set<Integer> kill = new HashSet<>();
        private final Set<Integer> lvEntry = new HashSet<>();
        private final Set<Integer> lvExit = new HashSet<>();

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
        int nRegs = Math.max(6, numberOfRegs);
        int END_REG = nRegs - 4;
        this.NB_REG_MAX = END_REG - START_REG + 1;

        this.TMP_REG_1 = nRegs - 1;
        this.TMP_REG_2 = nRegs - 2;

        this.REG_SPILL_PTR = nRegs - 3;

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

        // Construction du graphe de conflit
        buildConflictGraph();

        // Coloration du graphe de conflit
        this.colorSize = conflictGraph.color();

        return applyAllocation();
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
                Set<Integer> lvEntry = new HashSet<>(block.gen);
                for(Integer exit : lvExit){
                    if(!block.kill.contains(exit)){
                        lvEntry.add(exit);
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
                    if(!block.kill.contains(reg)){
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
     * Permet de récupérer les registres utilisés dans une instruction
     *
     * @param instruction           L'instruction dans laquelle chercher les registres
     * @return                      Les registres utilisés
     */
    private List<Integer> getReadRegisters(Instruction instruction){
        List<Integer> readRegisters = new ArrayList<>();

        // Cas 1 : L'instruction est une instruction UAL
        if(instruction instanceof UAL ual){

            // L'opération XOR entre deux mêmes registres renvoie toujours 0 : c'est ce qui permet l'initialisation d'une variable, qui n'était donc pas vivante avant
            if(!instruction.getName().equals(UAL.Op.XOR.toString()) || ual.getSr1() != ual.getSr2()){
                readRegisters.add(ual.getSr1());
                readRegisters.add(ual.getSr2());
            }

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

        // On ne prend pas en compte les registres réservés
        readRegisters.removeIf(reg -> reg < START_REG);

        return readRegisters;
    }

    /**
     * Permet de récupérer le registre écrasé dans une instruction
     *
     * @param instruction           L'instruction dans laquelle chercher le registre
     * @return                      Le registre écrasé
     */
    private Integer getWrittenRegister(Instruction instruction){
        Integer write = null;

        // Cas 1 : L'instruction est une instruction UAL
        if(instruction instanceof UAL ual){
            write = ual.getDest();
        }

        // Cas 2 : L'instruction est une instruction UAL immédiate
        else if(instruction instanceof UALi uali){
            write = uali.getDest();
        }

        // Cas 3 : L'instruction est une instruction mémoire
        else if(instruction instanceof Mem mem){

            // Sous-cas 1 : L'instruction est un LD
            if(mem.getName().equals(Mem.Op.LD.toString())){
                write = mem.getDest();
            }

            // Sous-cas 2 : L'instruction est un ST (ne rien faire)
        }

        // Cas 4 : L'instruction est une instruction d'entrée-sortie
        else if(instruction instanceof IO io){

            // Sous-cas 1 : L'instruction est un IN ou un READ
            if(io.getName().equals(IO.Op.IN.toString()) || io.getName().equals(IO.Op.READ.toString())){
                write = io.getReg();
            }

            // Sous-cas 2 : L'instruction est un OUT ou un PRINT (ne rien faire)
        }

        // On ne prend pas en compte les registres réservés
        if(write != null && write < START_REG){
            return null;
        }
        return write;
    }

    /**
     * Construis le graphe de conflit des registres utilisés
     *
     */
    private void buildConflictGraph(){
        this.conflictGraph = new UnorientedGraph<Integer>();

        // Étape 1 : Initialisation des sommets du graphe
        for(InstructionBlock block : blocks){
            for(Instruction instruction : block.instructions){
                Integer write = getWrittenRegister(instruction);
                if(write != null && write >= START_REG){
                    conflictGraph.addVertex(write);
                }

                for(Integer read : getReadRegisters(instruction)){
                    if(read >= START_REG){
                        conflictGraph.addVertex(read);
                    }
                }
            }
        }

        // Étape 2 : Construction des arêtes du graphe
        for(InstructionBlock block : blocks) {
            Set<Integer> currentlyLive = new HashSet<>(block.lvExit);

            // On lit les instructions à l'envers
            for(int i = block.instructions.size() - 1; i >= 0; i--){
                Instruction instruction = block.instructions.get(i);

                Integer write = getWrittenRegister(instruction);
                List<Integer> reads = getReadRegisters(instruction);

                if(write != null && write >= START_REG){

                    // On relie les registres en conflit
                    for(Integer liveReg : currentlyLive){
                        if(!liveReg.equals(write)){
                            conflictGraph.addEdge(write, liveReg);
                        }
                    }

                    // Puisque l'on lit à l'envers, le registre n'était pas vivant avant : on l'enlève
                    currentlyLive.remove(write);
                }

                // Les instructions utilisées étaient vivantes avant cette instruction : on les ajoute
                for(Integer read : reads){
                    if(read >= START_REG){
                        currentlyLive.add(read);
                    }
                }
            }
        }
    }

    /**
     * Permet de récupérer le programme après l'échange des anciens registres par les nouveaux
     *
     * @return                                  Le programme final
     * @throws IllegalArgumentException         Si une opération n'est pas reconnue dans le langage assembleur
     */
    private Program applyAllocation() throws IllegalArgumentException {
        Program newProgram = new Program();

        newProgram.addInstruction(new UAL(UAL.Op.XOR, REG_SPILL_PTR, REG_SPILL_PTR, REG_SPILL_PTR));
        newProgram.addInstruction(new UALi(UALi.Op.ADD, REG_SPILL_PTR, REG_SPILL_PTR, START_SPILL_ADDR));

        Set<String> functionLabels = new HashSet<>();

        // On parcourt tout le programme pour trouver les labels des fonctions (des CALL)
        for (Instruction instr : this.program.getInstructions()) {
            if (instr instanceof JumpCall && instr.getName().equals(JumpCall.Op.CALL.toString())) {
                functionLabels.add(((JumpCall) instr).getAddress());
            }
        }

        // On récupère la taille du Spill
        int spillSize = Math.max(0, colorSize - NB_REG_MAX);

        for (Instruction instruction : this.program.getInstructions()) {

            // Gestion de la pile
            String label = instruction.getLabel();
            if(label != null && functionLabels.contains(label) && spillSize > 0){

                // La nouvelle instruction devient celle avec le label de la fonction
                Instruction allocInstruction = new UALi(label, UALi.Op.ADD, REG_SPILL_PTR, REG_SPILL_PTR, spillSize);
                instruction.setLabel("");
                newProgram.addInstruction(allocInstruction);
            }
            if(instruction instanceof Ret && spillSize > 0){
                newProgram.addInstruction(new UALi(UALi.Op.SUB, REG_SPILL_PTR, REG_SPILL_PTR, spillSize));
            }

            // Cas 1 : Instruction UAL
            if (instruction instanceof UAL ual) {

                // Registre Destination
                int dest = getPhysicalRegister(ual.getDest());
                dest = dest == -1 ? TMP_REG_1 : dest;

                // Registre Source 1
                int sr1 = getPhysicalRegister(ual.getSr1());
                if(sr1 == -1){
                    newProgram.addInstructions(loadSpill(ual.getSr1(), TMP_REG_1));
                    sr1 = TMP_REG_1;
                }

                // Registre source 2
                int sr2 = getPhysicalRegister(ual.getSr2());
                if(sr2 == -1){
                    newProgram.addInstructions(loadSpill(ual.getSr2(), TMP_REG_2));
                    sr2 = TMP_REG_2;
                }

                // Instruction modifiée
                newProgram.addInstruction(new UAL(
                        ual.getLabel(),
                        UAL.Op.valueOf(ual.getName()),
                        dest,
                        sr1,
                        sr2
                ));

                // Si dest était dans le Spill, on remet le résultat en mémoire
                if(dest == TMP_REG_1){
                    newProgram.addInstructions(storeSpill(ual.getDest(), TMP_REG_2, dest));
                }
            }

            // Cas 2 : Instruction UAL immédiate
            else if(instruction instanceof UALi uali){

                // Registre Destination
                int dest = getPhysicalRegister(uali.getDest());
                dest = dest == -1 ? TMP_REG_1 : dest;

                // Registre Source
                int sr = getPhysicalRegister(uali.getSr());
                if(sr == -1){
                    newProgram.addInstructions(loadSpill(uali.getSr(), TMP_REG_1));
                    sr = TMP_REG_1;
                }

                // Instruction modifiée
                newProgram.addInstruction(new UALi(
                        uali.getLabel(),
                        UALi.Op.valueOf(uali.getName()),
                        dest,
                        sr,
                        uali.getImm()
                ));

                // Si dest était dans le Spill, on remet le résultat en mémoire
                if(dest == TMP_REG_1){
                    newProgram.addInstructions(storeSpill(uali.getDest(), TMP_REG_2, dest));
                }
            }

            // Cas 3 : Instruction de saut conditionnel
            else if(instruction instanceof CondJump condJump){

                // Registre Source 1
                int sr1 = getPhysicalRegister(condJump.getSr1());
                if(sr1 == -1){
                    newProgram.addInstructions(loadSpill(condJump.getSr1(), TMP_REG_1));
                    sr1 = TMP_REG_1;
                }

                // Registre source 2
                int sr2 = getPhysicalRegister(condJump.getSr2());
                if(sr2 == -1){
                    newProgram.addInstructions(loadSpill(condJump.getSr2(), TMP_REG_2));
                    sr2 = TMP_REG_2;
                }

                // Instruction modifiée
                newProgram.addInstruction(new CondJump(
                        condJump.getLabel(),
                        CondJump.Op.valueOf(condJump.getName()),
                        sr1,
                        sr2,
                        condJump.getAddress()
                ));
            }

            // Cas 4 : Instruction mémoire : LD
            else if(instruction instanceof Mem mem && instruction.getName().equals(Mem.Op.LD.toString())){

                // Registre Destination
                int dest = getPhysicalRegister(mem.getDest());
                dest = dest == -1 ? TMP_REG_1 : dest;

                // Registre Adresse
                int addr = getPhysicalRegister(mem.getAddress());
                if(addr == -1){
                    newProgram.addInstructions(loadSpill(mem.getAddress(), TMP_REG_2));
                    addr = TMP_REG_2;
                }

                // Instruction modifiée
                newProgram.addInstruction(new Mem(
                        mem.getLabel(),
                        Mem.Op.valueOf(mem.getName()),
                        dest,
                        addr
                ));

                // Si dest était dans le Spill, on remet le résultat en mémoire
                if(dest == TMP_REG_1){
                    newProgram.addInstructions(storeSpill(mem.getDest(), TMP_REG_2, dest));
                }
            }

            // Cas 5 : Instruction mémoire : ST
            else if(instruction instanceof Mem mem && instruction.getName().equals(Mem.Op.ST.toString())){

                // Registre Destination
                int val = getPhysicalRegister(mem.getDest());
                if(val == -1){
                    newProgram.addInstructions(loadSpill(mem.getDest(), TMP_REG_1));
                    val = TMP_REG_1;
                }

                // Registre Adresse
                int addr = getPhysicalRegister(mem.getAddress());
                if(addr == -1){
                    newProgram.addInstructions(loadSpill(mem.getAddress(), TMP_REG_2));
                    addr = TMP_REG_2;
                }

                // Instruction modifiée
                newProgram.addInstruction(new Mem(
                        mem.getLabel(),
                        Mem.Op.valueOf(mem.getName()),
                        val,
                        addr
                ));
            }

            // Cas 6 : Instruction IO
            else if(instruction instanceof IO io){
                int reg = getPhysicalRegister(io.getReg());
                String op = instruction.getName();

                // Sous-cas 1 : Instructions OUT ou PRINT
                if(op.equals(IO.Op.OUT.toString()) || op.equals(IO.Op.PRINT.toString())){
                    if(reg == -1){
                        newProgram.addInstructions(loadSpill(io.getReg(), TMP_REG_1));
                        reg = TMP_REG_1;
                    }
                    newProgram.addInstruction(new IO(
                            io.getLabel(),
                            IO.Op.valueOf(io.getName()),
                            reg
                    ));
                }

                // Sous-cas 2 : Instructions IN ou READ
                if(op.equals(IO.Op.IN.toString()) || op.equals(IO.Op.READ.toString())){
                    if(reg == -1){
                        reg = TMP_REG_1;
                    }
                    newProgram.addInstruction(new IO(
                            io.getLabel(),
                            IO.Op.valueOf(io.getName()),
                            reg
                    ));

                    // Si reg était dans le Spill, on remet le résultat en mémoire
                    if(reg == TMP_REG_1){
                        newProgram.addInstructions(storeSpill(io.getReg(), TMP_REG_2, reg));
                    }
                }
            }

            // Cas 7 : Instructions sans registres
            else{
                newProgram.addInstruction(instruction);
            }
        }

        return newProgram;
    }

    /**
     * Permet de récupérer le registre physique associé à un registre virtuel
     *
     * @param virtualRegister           Registre virtuel correspondant à l'ancien registre du programme linéaire
     * @return                          Registre physique correspondant au nouveau registre après coloration, -1 s'il dépasse le nombre de registres disponibles
     */
    private int getPhysicalRegister(int virtualRegister){
        if(virtualRegister < START_REG){
            return virtualRegister;
        }

        int physicalRegister = conflictGraph.getColor(virtualRegister);
        if(physicalRegister >= 0){
            if(physicalRegister < NB_REG_MAX) {
                return physicalRegister + START_REG;
            } else{
                return -1;
            }
        }

        throw new RuntimeException("Registre R" + virtualRegister + " non alloué !");
    }

    /**
     * Génère le code assembleur pour STORE un registre physique dans le Spill
     *
     * @param virtualRegister           Le registre virtuel à STORE
     * @param regAddr                   Le registre dans lequel on souhaite stocker l'adresse
     * @param regVal                    Le registre contenant la valeur à STORE
     * @return                          Le programme qui permet de STORE le registre physique dans le Spill
     */
    private Program storeSpill(int virtualRegister, int regAddr, int regVal){
        Program newProgram = new Program();

        int color = conflictGraph.getColor(virtualRegister);
        int offset = color - NB_REG_MAX + 1;

        newProgram.addInstruction(new UALi(UALi.Op.SUB, regAddr, REG_SPILL_PTR, offset));

        newProgram.addInstruction(new Mem(Mem.Op.ST, regVal, regAddr));

        return newProgram;
    }

    /**
     * Génère le code assembleur pour LOAD un registre physique du Spill
     *
     * @param virtualRegister           Le registre virtuel à LOAD
     * @param regAddr                   Le registre dans lequel on souhaite stocker l'adresse
     * @return                          Le programme qui permet de LOAD le registre physique dans le Spill
     */
    private Program loadSpill(int virtualRegister, int regAddr){
        Program newProgram = new Program();

        int color = conflictGraph.getColor(virtualRegister);
        int offset = color - NB_REG_MAX + 1;

        newProgram.addInstruction(new UALi(UALi.Op.SUB, regAddr, REG_SPILL_PTR, offset));

        newProgram.addInstruction(new Mem(Mem.Op.LD, regAddr, regAddr));

        return newProgram;
    }
}
