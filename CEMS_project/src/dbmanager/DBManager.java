/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package dbmanager;

import cems_project.Fragment;
import cems_project.Metabolito;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import exceptions.WrongRequestException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import static utilities.fileIO.readStringFromFile;

/**
 *
 * @author alberto.gildelafuent
 */
public class DBManager {

    protected Connection connection;
    protected Statement statement;

    /**
     * Method to connect to the database
     *
     * @param bd JDBC String to coonect -> Example
     * "jdbc:mysql://localhost/<DATABASE_NAME>/?useSSL=false&serverTimezone=UuseSSLTC
     * @param usuario username of the database -> Example -> Root
     * @param clave "password of the database" -> example -> password
     */
    public void connectToDB(String bd, String usuario, String clave) {
        try {
            // MySQL driver registered
            //DriverManager.registerDriver(new org.gjt.mm.mysql.Driver());

            // get or open the DatabaseConnection
            connection = DriverManager.getConnection(bd, usuario, clave);       //getConnection is a static method
            statement = this.connection.createStatement();
            //create an statement, pass the query and execute
        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    //----------------------------------------------------------------------------------------------------------
    //métodos nuevos:
    public void insertMetabolite(Metabolito m) {

        //select id donde coincida la INCHI
        String sql = "SELECT compound_id FROM compound_identifiers WHERE inchi LIKE ?";
        try {
            PreparedStatement ps = this.connection.prepareStatement(sql);
            ps.setString(1, m.getINCHI());
            int compound_id = getInt(ps);
            System.out.println("\ncompound_id: " + compound_id);

            if (compound_id == 0) {
                //tenemos que insertar toda la información correspondiente:
                System.out.println("El metabolito no estaba insertado. Lo insertamos.");
                //metemos el compuesto y guardamos el id asignado por MySQL
                compound_id = insertCompound(m);

                //insertamos la estructura:  INCHI, INCHI_KEY Y SMILES (hay que calcularlo)
                insertCompoundIdentifiers(compound_id, m.getINCHI());
            }

            // metemos las referencias a la estructura OPTIONALLY, CHECK IF THEY ALREADY EXISTS
            //if they do not exist, we do not have to insert anything
            //puede haber más de una referencia ya que la clave primaria está compuesta por 2 valores
            insertHMDB(compound_id, m.getRefHMDB());
            insertPC(compound_id, m.getRefPubChem());

            // SELECT CEEXPPROP ID FROM EXPERIMENTAL PROPERTIES NEG, INVERSO, TEMP y BUFFER
            sql = ConstantQueries.SELECT_CE_EXP_PROP;
            //"SELECT ce_exp_prop_id FROM ce_experimental_properties WHERE buffer = ? AND temperature = ? AND ionization_mode = ? AND polarity = ?"
            ps = this.connection.prepareStatement(sql);
            ps.setInt(1, ConstantQueries.BUFFER);
            ps.setInt(2, ConstantQueries.TEMPERATURE);
            ps.setInt(3, ConstantQueries.IONIZATION_MODE);
            ps.setInt(4, ConstantQueries.POLARITY);
            int ce_ex_prop_id = getInt(ps);
            //System.out.println("ce_ex_prop_id: " + ce_ex_prop_id);

            // INSERT EFF MOB
            int ce_eff_mob_id = insertCeEffMob(compound_id, ce_ex_prop_id, m);  //java.sql.SQLIntegrityConstraintViolationException: Duplicate entry
            //System.out.println("ce_eff_mob_id" + ce_eff_mob_id);

            // INSERT METADATA WITH METH SULFONE AND PARACETAMOL
            insertCeExpPropMet(ce_eff_mob_id, m);       //java.sql.SQLIntegrityConstraintViolationException: Cannot add or update a child row

            // INSERT FRAGMENTS
            //insertamos los fragmentos
            List<Fragment> fragments = m.getFragments();
            //por cada fragment de la lista
            insertCompCeProdIon(compound_id, ce_eff_mob_id, fragments);     //java.sql.SQLIntegrityConstraintViolationException: Cannot add or update a child row

        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public int insertCompound(Metabolito m) {
        //devuelve el id del compound para usarlo como foreign key

        int compound_id = 0;
        String sql = ConstantQueries.INSERT_COMPOUNDS;
        //"INSERT INTO compounds (compound_name, formula, mass) VALUES (?, ?, ?)"
        try {
            PreparedStatement ps = this.connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, m.getCompound());
            ps.setString(2, m.getFormula());
            ps.setDouble(3, m.getM());
            ps.executeUpdate();                 //insertamos la info

            //hallamos el compound id del metabolito que acabamos de introducir
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    compound_id = rs.getInt(1);
                    //System.out.println("CompoundId: " + compound_id);
                }
                rs.close();
            } catch (SQLException ex) {
                Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
            }
        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
        }
        return compound_id;
    }

    public void insertCompoundIdentifiers(int compound_id, String inchi) {

        String sql = ConstantQueries.INSERT_COMP_IDENT;
        //"INSERT INTO compound_identifiers (compound_id, inchi, inchi_key, smiles) VALUES (?, ?, ?, ?)"
        try {
            PreparedStatement ps = this.connection.prepareStatement(sql);
            ps.setInt(1, compound_id);
            ps.setString(2, inchi);
            String key = ChemSpiderREST.getINCHIKeyFromInchi(inchi);
            String smiles = ChemSpiderREST.getSMILESFromInchi(inchi);
            ps.setString(3, key);
            ps.setString(4, smiles);
            ps.executeUpdate();

        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ioe) {
            System.out.println("ex: " + ioe);
        } catch (WrongRequestException wre) {
            System.out.println(wre);
        }
    }

    public void insertHMDB(int compound_id, String HMDB) {
        String sql = ConstantQueries.INSERT_COMP_HMDB;
        //"INSERT INTO compounds_hmdb (hmdb_id, compound_id) VALUES (?, ?)"
        try {
            if (HMDB != null) {
                //we just have to add the reference when it exists
                PreparedStatement ps = this.connection.prepareStatement(sql);
                ps.setString(1, HMDB);
                ps.setInt(2, compound_id);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            //Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
            if (ex.getErrorCode() == 1062) { //code de Duplicate Entry
                System.out.println("No se inserta la referencia HMDB porque ya estaba metida.");
            }
        }
    }

    public void insertPC(int compound_id, Integer pc) {
        String sql = ConstantQueries.INSERT_COMP_PC;
        //"INSERT INTO compounds_pc (pc_id, compound_id) VALUES (?, ?)"

        try {

            if (pc != null) {
                PreparedStatement ps = this.connection.prepareStatement(sql);
                ps.setInt(1, pc);

                ps.setInt(2, compound_id);
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            //Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
            if (ex.getErrorCode() == 1062) { //code de Duplicate Entry
                System.out.println("No se inserta la referencia PubChem porque ya estaba metida.");
            }
        }
    }

    public int insertCeEffMob(int compound_id, int ce_ex_prop_id, Metabolito m) {
        //returns el id que se acaba de insertar (ce_eff_mob_id)
        String sql = ConstantQueries.INSERT_CE_EFF_MOB;
        //INSERT INTO ce_eff_mob(ce_compound_id, ce_exp_prop_id, cembio_id, eff_mobility) VALUES(?, ?, ?, ?)"
        int ce_eff_mob_id = 0;
        try {
            PreparedStatement ps = this.connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setInt(1, compound_id);
            ps.setInt(2, ce_ex_prop_id);

            //tenemos que hacer un get del cembio_id
            Integer cembio_id = null;
            String query = "Select MAX(cembio_id) from ce_eff_mob";
            ResultSet rset = statement.executeQuery(query);
            if (rset.next()) {
                cembio_id = rset.getInt(1);
                //System.out.println(cembio_id);
            }
            rset.close();
            cembio_id++;    //el id insertado es el siguiente al ultimo que se ha leído
            ps.setDouble(3, cembio_id);

            if (m.getEff_mobility() == null) {
                ps.setNull(4, java.sql.Types.NULL);
            } else {
                ps.setDouble(4, m.getEff_mobility());   //CAN BE NULL
            }
            ps.executeUpdate();
            System.out.println("Insertamos la EffectiveMobility");

            //ahora tenemos que obtener el ce_eff_mob_id que se acaba de insertar
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    ce_eff_mob_id = rs.getInt(1);
                    // ceSystem.out.println("Last ce_eff_mob_id: " + ce_eff_mob_id);       //este id es el que se manda a el insert de los fragments
                }
                rs.close();
            } catch (SQLException ex) {
                Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
            }
        } catch (SQLException ex) {
            //Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
            if (ex.getErrorCode() == 1062) { //code de Duplicate Entry
                System.out.println("No se inserta la información relativa a Effective Mobility porque ya estaba metida.");
            }
        }
        return ce_eff_mob_id;
    }

    public void insertCeExpPropMet(int ce_eff_mob_id, Metabolito m) {

        String sql = ConstantQueries.INSERT_CE_EXP_PROP_META;
        //"INSERT INTO ce_experimental_properties_metadata(ce_eff_mob_id, experimental_mz, capillary_voltage, capillary_length, bge_compound_id, absolute_MT, relative_MT) VALUES (?, ?, ?, ?, ?, ?, ?)"
        try {
            PreparedStatement ps = this.connection.prepareStatement(sql);

            //cuidado con los valores que son null
            //insertamos lo referente a la metionina sulfona
            ps.setInt(1, ce_eff_mob_id);
            ps.setDouble(2, m.getM_Z());
            ps.setInt(3, ConstantQueries.CAPILLARY_VOLTAGE);
            ps.setInt(4, ConstantQueries.CAPILLARY_LENGTH);
            ps.setInt(5, ConstantQueries.BGE_COMPOUND_ID_METS);

            if (m.getMT_Mets() == null) {
                ps.setNull(6, java.sql.Types.NULL);
            } else {
                ps.setDouble(6, m.getMT_Mets());
            }
            if (m.getRMT_Mets() == null) {
                ps.setNull(7, java.sql.Types.NULL);
            } else {
                ps.setDouble(7, m.getRMT_Mets());
            }

            ps.executeUpdate();

            //insertamos lo referente al paracetamol
            ps.setInt(1, ce_eff_mob_id);
            ps.setDouble(2, m.getM_Z());
            ps.setInt(3, ConstantQueries.CAPILLARY_VOLTAGE);
            ps.setInt(4, ConstantQueries.CAPILLARY_LENGTH);
            ps.setInt(5, ConstantQueries.BGE_COMPOUND_ID_MES);

            if (m.getMT_Mes() == null) {
                ps.setNull(6, java.sql.Types.NULL);
            } else {
                ps.setDouble(6, m.getMT_Mes());
            }
            if (m.getMT_Mes() == null) {
                ps.setNull(7, java.sql.Types.NULL);
            } else {
                ps.setDouble(7, m.getRMT_Mes());
            }

            ps.executeUpdate();

            System.out.println("Insertamos las ce_experimental_properties");
        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    public void insertCompCeProdIon(int compound_id, int ce_eff_mob_id, List<Fragment> fragments) {
        String sql = ConstantQueries.INSERT_COMP_CE_PROD_ION;
        //"INSERT INTO compound_ce_product_ion (ion_source_voltage, ce_product_ion_mz, ce_product_ion_intensity,  ce_product_ion_type, ce_eff_mob_id, compound_id_own) VALUES (?, ?, ?, 'fragment', ?, ?)"

        Double intensity;
        try {
            PreparedStatement ps = this.connection.prepareStatement(sql);

            for (int i = 0; i < fragments.size(); i++) {
                ps.setInt(1, ConstantQueries.ION_SOURCE_VOLTAGE);
                ps.setDouble(2, fragments.get(i).getM_Z());

                intensity = fragments.get(i).getIntensity();

                if (intensity == null) {
                    ps.setNull(3, java.sql.Types.NULL);
                } else {
                    ps.setDouble(3, intensity);
                }

                ps.setInt(4, ce_eff_mob_id);
                ps.setInt(5, compound_id);
                ps.executeUpdate();
                System.out.println("Insertamos los fragments");
            }

        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
            System.out.println("\n");
        } catch (NullPointerException npe) {
            //System.out.println("El compuesto con ID " + id + " No ha insertado el fragmento con m/z = " + m_z + " porque es null");
        }

    }

    //---------------------------------------------------------------------------------------------------------
    /**
     * It executes the query and returns the first int returned by the query.
     *
     * @param query
     * @return the ID of the query or 0 if the result is null
     */
    public int getInt(String query) {
        int id = 0;
        // Be aware that the connection should be initialized (calling the method connectToDB)

        try {
            PreparedStatement ps = this.connection.prepareStatement(query);
            ResultSet rs = ps.executeQuery();   //es un iterador

            if (rs.next()) {
                id = rs.getInt(1);  //este uno es que accede a la primera columna de la query que yo le paso
            }
            rs.close();

        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
        }
        return id;
    }

    public int getInt(PreparedStatement ps) {
        int id = 0;
        // Be aware that the connection should be initialized (calling the method connectToDB)

        try {
            ResultSet rs = ps.executeQuery();   //es un iterador

            if (rs.next()) {
                id = rs.getInt(1);  //este uno es que accede a la primera columna de la query que yo le paso
            }
            rs.close();

        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
        }
        return id;
    }

    /**
     * TO DO
     *
     * @param mz
     * @param tolerance
     * @return
     */
    public int getIdsFromMZ(Double mz, Double tolerance) {

        return 0;
    }

    //habrá que hacer también el resto de gets en función de lo que necesitemos
    /**
     * It executes the query and returns the first String returned by the query
     *
     * @param query
     * @return the String of the query or ??? if the result is null
     */
    public String getString(String query) {
        String word = null;
        try {
            ResultSet rs = statement.executeQuery(query);
            if (rs.next()) {
                word = rs.getString(1);
            }
            rs.close();
        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
        }
        return word;
    }

    /**
     * Metodo para obtener un metabolito por JDBC. It should throw a exception
     * if the query is not from metabolites
     *
     * @param ResultSet rs containing the row of the current metabolite
     * @return
     */
    private Metabolito getMetabolito(ResultSet rs) {    //tan solo lee el metabolito de al fila que se le ha pasado como parametro
        try {
            int id = rs.getInt("id");
            //int id = rs.getInt(1);
            //System.out.println("ID: " + id);

            String Compd_name = rs.getString("Compound_name");        //MEJOR PORNERLO CON EL NOMBRE DE LAS COLUMNAS
            //String Compd_name = rs.getString(2);
            //System.out.println("Compound name: " + Compd_name);

            String formula = rs.getString("Formula");
            //System.out.println("Formula: " + formula);
            double m_mass = rs.getDouble("Monoisotopic_mass");
            //System.out.println("Monoisotopic Mass: " + m_mass);
            double m_z = rs.getDouble("M_z");
            //System.out.println("M_z: " + m_z);
            double mt_compnd = rs.getDouble("Mt_compnd");
            // System.out.println("MT_compnd: " + mt_compnd);
            double mt_mets = rs.getDouble("Mt_mets");
            //System.out.println("MT_mets: " + mt_mets);
            double rmt_mets = rs.getDouble("Rmt_mets");
            //System.out.println("RMT_mets: " + rmt_mets);
            double mt_mes = rs.getDouble("Mt_mes");
            //System.out.println("MT_mes: " + mt_mes);
            double rmt_mes = rs.getDouble("Rmt_mes");
            //System.out.println("RMT_mes: " + rmt_mes);

            // Select de los fragmentos. Creando un metodo getFragmentos, solo reciba el parámetro int ID
            List<Fragment> fragments = getFragments(id);
            Metabolito m1 = new Metabolito(Compd_name, null, null, null, formula, m_mass, m_z, mt_compnd, mt_mets, rmt_mets, mt_mes, rmt_mes, fragments, null);
            return m1;
        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;
    }

    /**
     * It executes the query and returns the list of Fragments returned by the
     * query.
     *
     * @param query
     * @return the list of fragments or null
     */
    private List<Fragment> getFragments(int id) {

        String query = ConstantQueries.SELECTFRAGMENTSFROMID;
        List<Fragment> fragments = new LinkedList<>();
        try {
            PreparedStatement statement2 = this.connection.prepareStatement(query);
            statement2.setInt(1, id);   //para evitar la sql injection
            try {
                ResultSet rs = statement2.executeQuery();
                while (rs.next()) {
                    double m_z = rs.getDouble("m_z");
                    double intensity = rs.getDouble("intensity");
                    fragments.add(new Fragment(m_z, intensity));
                    //System.out.println("frag: " + fragments);
                }
                rs.close();
                return fragments;
            } catch (SQLException ex) {
                Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
                return null;
            }
        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }

    /**
     * TO DO It executes the query and returns the list of Fragments returned by
     * the mz and its tolerance
     *
     * @param mz mz of the fragments to find
     * @param tolerance tolerance in ppm
     * @return the list of fragments or null
     */
    private List<Fragment> getFragmentsFromMZRange(float mz, float tolerance) {

        // hay que crear la query para introducir la mz y la tolerancia por parametros
        // delta es el calculo de las ppm del mz
        String query = "Select * from fragments where mz < mz+delta and mz > mz-delta";
        List<Fragment> fragments = new LinkedList<>();

        try {
            statement.execute(query);
            ResultSet rs = statement.executeQuery(query);
            while (rs.next()) {
                //fragments.add(new Fragment(rs.getDouble(3)));   //la columna que contiene el m_z es la tres
                //mejor separarlo
                double m_z = rs.getDouble("M_z");
                fragments.add(new Fragment(m_z));
                //System.out.println("frag: " + fragments);
            }
            rs.close();
            return fragments;
        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }

    /**
     *
     * @param query the SQL query to execute. It should contain the attributes:
     * @return list of metabolites
     */
    public List<Metabolito> getMetabolitos(String query) {
        List<Metabolito> metabs = new LinkedList<Metabolito>();
        try {
            statement.execute(query);
            ResultSet rs = statement.executeQuery(query);
            while (rs.next()) {
                //System.out.println("ROW: " + rs.getRow());
                Metabolito m1 = getMetabolito(rs);  //no podemos estar trabajando con dos result sets distintos
                metabs.add(m1);
                //System.out.println(metabs);

            }
            rs.close();
        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
        }
        return metabs;
    }

    /**
     *
     * @param metabolito
     * @throws Exception
     */
    public void insertMetabolito(Metabolito metabolito) /*throws Exception*/ {

        System.out.println("El metabolito a insertar es:");
        System.out.println(metabolito);
        // mediante una llamada al metodo insertMetabolito (devuelve el ID generado.
        // mediante otra llamada la insercion de los fragmentos.
        // PARA INSERTS SE UTILIZA executeUPDATE. Una vez ejecuto el insert con
        // parametros PREPAREDSTATEMENT para crearlo,
        // ps.setTIPO(POSICION,VALOR);
        // ps.executeQuery(); IMPORTANTE SIN PARAMETROS QUE ES UN PREPAREDSTATEMENT
        // ps.getGeneratedKeys();
        // SI SOLO HE HECHO UN INSERT, devolverá un único entero correspondiente
        // a la fila insertada
        String query = ConstantQueries.INSERTINTOMETABOLITES;
        try {
            PreparedStatement ps = this.connection.prepareStatement(query, PreparedStatement.RETURN_GENERATED_KEYS);
            ps.setString(1, metabolito.getCompound());
            ps.setString(2, metabolito.getFormula());
            ps.setDouble(3, metabolito.getM());
            ps.setDouble(4, metabolito.getM_Z());
            try {
                ps.setDouble(5, metabolito.getMT_compound());
            } catch (NullPointerException e) {
                ps.setNull(5, 0);
            }
            try {
                ps.setDouble(6, metabolito.getMT_Mets());
            } catch (NullPointerException e) {
                ps.setNull(6, 0);
            }
            try {
                ps.setDouble(7, metabolito.getRMT_Mets());
            } catch (NullPointerException e) {
                ps.setNull(7, 0);
            }
            try {
                ps.setDouble(8, metabolito.getMT_Mes());
            } catch (NullPointerException e) {
                ps.setNull(8, 0);
            }
            try {
                ps.setDouble(9, metabolito.getRMT_Mes());
            } catch (NullPointerException e) {
                ps.setNull(9, java.sql.Types.NULL); //Types.NULL = 0
            }

            List<Fragment> fragments = metabolito.getFragments();
            //------------------
            try {
                ps.executeUpdate();                 //este es el sentence que insertea la info
                //usamos update despues de tener el statement y la query (update para insert, update y delete)
                //System.out.println("insertado");

                //hallamos el id del metabolito que acabamos de introducir para mandarlo a insertFragmentos
                int id = 0;
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        id = rs.getInt(1);
                        System.out.println("Last id: " + id);       //este id es el que se manda a el insert de los fragments
                    }
                    rs.close();
                    System.out.println("ahora se insertan los fragmentos del metabolito " + id);
                    insertFragments(id, fragments);

                } catch (SQLException ex) {
                    System.out.println("Metabolito: " + metabolito + " NOT INSERTED. Check Excel File. AUTO_GENERATED_KEYS FAILED" + ex.getMessage());
                    Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
                }
            } catch (SQLException ex) {
                System.out.println("Metabolito: " + metabolito + " NOT INSERTED. Check Excel File. INSERT FAILED" + ex.getMessage());
                Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
            }
        } catch (SQLException ex) {

            System.out.println("Metabolito: " + metabolito + " NOT INSERTED. Check Excel File. PREPARE STATEMENT COULD NOT BE CREATED" + ex.getMessage());
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
        }

    }

    /**
     *
     * @param id which is the one from the last metabolite inserted
     * @throws Exception
     */
    public void insertFragments(int id, List<Fragment> fragments)/*throws Exception*/ {

        String query = ConstantQueries.INSERTFRAGMENTSFROMID;
        Double m_z = null;
        Double intensity = null;
        try {
            PreparedStatement ps = this.connection.prepareStatement(query);
            ps.setInt(1, id);

            for (int i = 0; i < fragments.size(); i++) {

                m_z = fragments.get(i).getM_Z();
                ps.setDouble(2, m_z);

                intensity = fragments.get(i).getIntensity();
                if (intensity == null) {

                    ps.setNull(3, java.sql.Types.NULL);
                } else {
                    ps.setDouble(3, intensity);
                }
                ps.executeUpdate();
            }

        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);
        } catch (NullPointerException npe) {
            System.out.println("El compuesto con ID " + id + " No ha insertado el fragmento con m/z = " + m_z + " porque es null");
        }

    }

    /**
     * SHOULD NOT BE USED..!!! It does not use preparedStaments so it is
     * vulnerable to SQL Injection
     *
     * @param query
     * @return the ID of the query or 0 if the result is null
     */
    private int exampleQueryToGetTheLastGeneratedIdFromAnInsert(String query) {
        int id = 0;
        // Be aware that the connection should be initialized (calling the method connectToDB

        try {

            statement.executeUpdate(query, Statement.RETURN_GENERATED_KEYS);
            try (ResultSet provRS = statement.getGeneratedKeys()) {
                if (provRS.next()) {
                    id = provRS.getInt(1);
                    System.out.println("Last id: " + id);
                }
                provRS.close();

            }
        } catch (SQLException ex) {
            Logger.getLogger(DBManager.class
                    .getName()).log(Level.SEVERE, null, ex);
        }
//        System.out.println("\n GENERATED KEY of " + actualizacion + " : " + id);
        return id;
    }

    public static void main(String[] args) {

        DBManager db = new DBManager();
        String filename = "resources/connectionData.pass";  //este file contiene los datos de acceso a la database (notación JSON: clave-valor)
        try {
            Gson gson = new Gson();
            String readJSONStr = readStringFromFile(filename);
            JsonElement element = gson.fromJson(readJSONStr, JsonElement.class);
            JsonObject jsonObj = element.getAsJsonObject();
            String dbName = jsonObj.get("db_name").getAsString();
            String dbUser = jsonObj.get("db_user").getAsString();
            String dbPassword = jsonObj.get("db_password").getAsString();

            // Here you can check the values obtained
            //System.out.println("DB_NAME: " + dbName + " DBUser: " + dbUser + " DBPassword: " + dbPassword);
            db.connectToDB("jdbc:mysql://localhost/" + dbName + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true", dbUser, dbPassword);

            //GET INT
            int id = db.getInt("select compound_id from compounds where formula = \"C5H11NO4\"");
            System.out.println(id);

            //GET STRING
            //String word = db.getString("Select \"asd\"");
            //System.out.println(word);
            //GETMETABOLITO
            //Metabolito m1 = db.getMetabolito("Select * from metabolites where ID = 2");
            //Metabolito m1 = db.getMetabolito("Select * from metabolites");
            //System.out.println("Metabolito 1:\n" + m1);
            //GETFRAGMENTOS
            //List<Fragment> fragments = db.getFragments(2);
            //System.out.println("Fragments: \n" + fragments);
            //GETMETABOLITOS
            //List<Metabolito> metabs = db.getMetabolitos("select * from metabolites where id = 1");
            //List<Metabolito> metabs = db.getMetabolitos("select * from metabolites");
            //System.out.println("Estos son los metabolitos almacenados: \n" + metabs);
            //List<Fragment> fragments = db.getFragments(2);
            //System.out.println("Estos son los fragmentos almacenados: \n" + fragments);
            // SI NO INSERTA NADA, AUTO_GENERATED KEYS DEVUELVE 0
            //int id_updated = db.exampleQueryToGetTheLastGeneratedIdFromAnInsert("update prueba set f1 = 2 where id=2");
            //System.out.println(id_updated);
            //int id_inserted = db.exampleQueryToGetTheLastGeneratedIdFromAnInsert("insert into prueba (f1) values (1)");
            //System.out.println(id_inserted);
            //INSERT METABOLITES
//            List<Fragment> fragments = new LinkedList();
//            fragments.add(new Fragment(78.9594));
//            fragments.add(new Fragment(96.9671));
//            fragments.add(new Fragment(138.9802));
//            Metabolito metabolito = new Metabolito("Fructose 1,6 Biphosphate", "C6H14O12P2", 339.9960, 338.9887, 9.739, 27.135, 0.36, 20.187, 0.48, fragments);
//            db.insertMetabolito(metabolito);
            //db.insertFragments(1, fragments);
        } catch (FileNotFoundException ex) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ex);

        } catch (IOException ioe) {
            Logger.getLogger(DBManager.class.getName()).log(Level.SEVERE, null, ioe);
        }
    }
}
